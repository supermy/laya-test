package com.laya

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一存储层(框架自带 SQLite,无新依赖)。
 * DDL 以 service/schema.sql 为权威,本文件以常量镜像 —— 改任一处必须同步另一处,并两侧同值递增 DB_VERSION/schema_version。
 *
 * 并发:SQLiteOpenHelper 单连接,框架内部对 getWritableDatabase 串行;WAL 减读写互斥。
 * 写入方:决策链(串行锁内)、网关线程、NanoHTTPD 线程池 —— 均非主线程。
 */
object Db {
  private const val DB_NAME = "laya.db"
  private const val DB_VERSION = 1 // = service/schema.sql meta.schema_version

  // ---- DDL(镜像 service/schema.sql) ----
  private val DDL = arrayOf(
    """CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
)""",
    """CREATE TABLE IF NOT EXISTS decisions (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  ts         INTEGER NOT NULL,
  date       TEXT NOT NULL,
  task       TEXT NOT NULL,
  type       TEXT NOT NULL DEFAULT 'decision',
  level      TEXT NOT NULL DEFAULT '未知',
  basis      TEXT NOT NULL DEFAULT '',
  state      TEXT NOT NULL DEFAULT '',
  answers    TEXT NOT NULL DEFAULT '{}',
  latency_ms INTEGER NOT NULL DEFAULT 0,
  via        TEXT NOT NULL DEFAULT '',
  llm        TEXT,
  UNIQUE(ts, task, type)
)""",
    "CREATE INDEX IF NOT EXISTS idx_dec_date ON decisions(date, task, level)",
    "CREATE INDEX IF NOT EXISTS idx_dec_ts   ON decisions(ts)",
    """CREATE TABLE IF NOT EXISTS tasks (
  id            TEXT PRIMARY KEY,
  label         TEXT NOT NULL,
  backend       TEXT NOT NULL DEFAULT 'cpu',
  enabled       INTEGER NOT NULL DEFAULT 1,
  state_key     TEXT NOT NULL DEFAULT 'text',
  model_dir     TEXT NOT NULL DEFAULT '',
  model_path    TEXT NOT NULL DEFAULT '',
  question_defs TEXT NOT NULL DEFAULT '[]',
  classes       TEXT,
  normal_label  TEXT,
  source        TEXT NOT NULL DEFAULT 'auto-scan',
  updated_at    INTEGER NOT NULL
)""",
    """CREATE TABLE IF NOT EXISTS collect_configs (
  task       TEXT PRIMARY KEY,
  enabled    INTEGER NOT NULL DEFAULT 0,
  samples    TEXT NOT NULL DEFAULT '[]',
  updated_at INTEGER NOT NULL
)""",
    """CREATE TABLE IF NOT EXISTS inbound (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          INTEGER NOT NULL,
  channel     TEXT NOT NULL,
  sender      TEXT NOT NULL DEFAULT '',
  subject     TEXT NOT NULL DEFAULT '',
  topic       TEXT NOT NULL DEFAULT '',
  text        TEXT NOT NULL DEFAULT '',
  task        TEXT NOT NULL DEFAULT '',
  decision_id INTEGER,
  status      TEXT NOT NULL DEFAULT 'pending',
  error       TEXT NOT NULL DEFAULT ''
)""",
    "CREATE INDEX IF NOT EXISTS idx_inbound_ts ON inbound(ts)",
    """CREATE TABLE IF NOT EXISTS templates (
  task       TEXT NOT NULL,
  locale     TEXT NOT NULL,
  kind       TEXT NOT NULL,
  key        TEXT NOT NULL DEFAULT '',
  body       TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(task, locale, kind, key)
)""",
    """CREATE TABLE IF NOT EXISTS eval_samples (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  source       TEXT NOT NULL DEFAULT 'tickets',
  ext_id       TEXT NOT NULL DEFAULT '',
  text         TEXT NOT NULL,
  expect_task  TEXT NOT NULL DEFAULT '',
  expect_label TEXT NOT NULL DEFAULT '',
  meta         TEXT NOT NULL DEFAULT '{}',
  created_at   INTEGER NOT NULL,
  UNIQUE(source, ext_id)
)""",
  )

  private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
      for (sql in DDL) db.execSQL(sql)
      db.execSQL("INSERT OR IGNORE INTO meta(key,value) VALUES('schema_version',?)", arrayOf(DB_VERSION.toString()))
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      // v1 无历史版本;后续版本在此逐步迁移
    }
    override fun onConfigure(db: SQLiteDatabase) {
      if (!db.isWriteAheadLoggingEnabled) db.enableWriteAheadLogging()
    }
  }

  @Volatile private var helper: Helper? = null
  @Volatile private var initialized = false

  /** 幂等初始化;未初始化时各方法自动走懒初始化(需带 Context 的入口先调)。
   *  初始化内容:建表 → 存量 JSONL 迁移 → prefs cfg.collect 一次性迁移 → 注入模板覆盖查找(ReplyTemplates.source)。 */
  @JvmStatic fun init(ctx: Context) {
    if (!initialized) synchronized(this) {
      if (!initialized) {
        helper = Helper(ctx.applicationContext)
        val app = ctx.applicationContext
        migrateJsonl(app)
        migrateCollect(app)
        ReplyTemplates.source = ReplyTemplates.TemplateSource { t, loc, kind, key -> templateGet(t, loc, kind, key) }
        initialized = true
      }
    }
  }

  /** 存量决策日志迁移:filesDir 外部目录 decisions-*.jsonl → decisions 表(幂等,每文件 meta 标记;完成后改名 .imported)。
   *  level 按统一口径重算(与 2026-10-16 前旧 Kotlin noul 口径相反,历史"高/低"分布会移动 —— 与 Node 侧 migrate-jsonl 一致)。 */
  private fun migrateJsonl(ctx: Context) {
    try {
      val dir = ctx.getExternalFilesDir(null) ?: return
      val files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("decisions-\\d{6}\\.jsonl")) } ?: return
      if (files.isEmpty()) return
      val d = helper!!.writableDatabase
      for (f in files) {
        if (metaLong(d, "migrated:${f.name}") > 0) continue
        var n = 0
        d.beginTransaction()
        try {
          for (line in f.readText().split("\n")) {
            if (line.isBlank()) continue
            try {
              val o = JSONObject(line)
              val task = o.optString("task")
              if (task.isEmpty()) continue
              if (o.optString("type") == "llm") {
                insertLlmRow(ctx, task, o.optLong("latencyMs"), o.optJSONObject("llm"), o.optString("error").ifEmpty { null })
              } else {
                val decoded = o.optJSONObject("decoded") ?: o.optJSONObject("answers") ?: continue
                insertDecision(ctx, task, o.optString("state"), decoded, o.optLong("latencyMs"))
              }
              n++
            } catch (_: Exception) {}
          }
          d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        d.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", arrayOf("migrated:${f.name}", n.toString()))
        f.renameTo(File(dir, f.name + ".imported"))
      }
    } catch (_: Throwable) {}
  }

  private fun db(ctx: Context? = null): SQLiteDatabase {
    if (helper == null) {
      check(ctx != null) { "Db 未初始化:请先 Db.init(context)" }
      init(ctx)
    }
    return helper!!.writableDatabase
  }

  // ---- 等级口径(镜像 service/db.mjs levelOfUnified:score 归一化 / noul>=0.5 命中=高 / choice 标签) ----
  private fun levelOf(answers: JSONObject): Pair<String, String> {
    var k = answers.keys()
    while (k.hasNext()) {
      val a = answers.optJSONObject(k.next()) ?: continue
      if (a.optString("type") != "score") continue
      val legend = a.optJSONObject("legend")
      val maxIdx = if (legend != null && legend.length() > 1) legend.length() - 1 else 4
      val score = a.optDouble("score")
      val n = if (maxIdx > 0) score / maxIdx else 0.0
      val lab = if (legend != null) legend.optString(Math.round(score).toString()) else ""
      val lv = if (n >= 0.66) "高" else if (n >= 0.33) "中" else "低"
      val basis = "score=" + String.format(Locale.US, "%.2f", score) + if (lab.isNullOrBlank()) "" else "($lab)"
      return lv to basis
    }
    k = answers.keys()
    while (k.hasNext()) {
      val a = answers.optJSONObject(k.next()) ?: continue
      if (a.optString("type") == "noul") {
        val p = a.optDouble("noul")
        return (if (p >= 0.5) "高" else "低") to String.format(Locale.US, "noul=%.2f", p) + if (p >= 0.5) "(命中)" else "(无信号)"
      }
    }
    k = answers.keys()
    while (k.hasNext()) {
      val a = answers.optJSONObject(k.next()) ?: continue
      if (a.optString("type") == "choice") {
        val c = a.optString("choice")
        return c to "choice=$c(conf=" + String.format(Locale.US, "%.2f", a.optDouble("confidence")) + ")"
      }
    }
    return "未知" to ""
  }

  private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private fun localDate(ts: Long): String = dateFmt.format(Date(ts))

  /** 读 meta 整型值;键不存在返回 0(simpleQueryForLong 在无行时抛 SQLiteDoneException,勿用于可缺行查询) */
  private fun metaLong(d: SQLiteDatabase, key: String): Long {
    d.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { c ->
      return if (c.moveToFirst()) (c.getString(0)?.toLongOrNull() ?: 0L) else 0L
    }
  }

  // ---- 写入 ----
  /** 决策日志入库(原 JSONL appendLog 语义:state 截 200 字符),返回 decisions.id */
  @JvmStatic
  fun insertDecision(ctx: Context?, task: String, state: String, decoded: JSONObject, ms: Long, via: String = ""): Long {
    val (level, basis) = levelOf(decoded)
    val v = ContentValues().apply {
      put("ts", System.currentTimeMillis())
      put("date", localDate(System.currentTimeMillis()))
      put("task", task)
      put("type", "decision")
      put("level", level)
      put("basis", basis)
      put("state", if (state.length > 200) state.substring(0, 200) else state)
      put("answers", decoded.toString())
      put("latency_ms", ms)
      put("via", via)
    }
    return db(ctx).insertWithOnConflict("decisions", null, v, SQLiteDatabase.CONFLICT_IGNORE)
  }

  /** LLM 升级行(type='llm';llm={model,content} 或 error) */
  @JvmStatic
  fun insertLlmRow(ctx: Context?, task: String, latencyMs: Long, llm: JSONObject?, error: String?): Long {
    val v = ContentValues().apply {
      put("ts", System.currentTimeMillis())
      put("date", localDate(System.currentTimeMillis()))
      put("task", task)
      put("type", "llm")
      put("latency_ms", latencyMs)
      if (llm != null) put("llm", llm.toString()) else put("llm", JSONObject().put("error", error ?: "").toString())
    }
    return db(ctx).insertWithOnConflict("decisions", null, v, SQLiteDatabase.CONFLICT_IGNORE)
  }

  // ---- 网关入站(客服自动采集等;镜像 service/db.mjs insertInbound/linkInbound 语义) ----
  /** 入站请求登记(status='pending'),返回 inbound.id;决策后经 linkInbound 回填 */
  @JvmStatic
  fun insertInbound(ctx: Context?, channel: String, topic: String, sender: String,
                    subject: String, text: String, task: String): Long {
    val v = ContentValues().apply {
      put("ts", System.currentTimeMillis())
      put("channel", channel)
      put("topic", topic)
      put("sender", sender)
      put("subject", subject)
      put("text", text)
      put("task", task)
      put("status", "pending")
    }
    return db(ctx).insert("inbound", null, v)
  }

  /** 回填决策结果与终态(status='done'|'failed'|'ignored') */
  @JvmStatic
  fun linkInbound(ctx: Context?, id: Long, decisionId: Long, status: String) {
    if (id <= 0) return
    val v = ContentValues().apply {
      if (decisionId > 0) put("decision_id", decisionId)
      put("status", status)
    }
    db(ctx).update("inbound", v, "id=?", arrayOf(id.toString()))
  }

  /** 入站持久统计(客服 tab 统计行;总数/今日/失败;COUNT(*) 恒有行,simpleQueryForLong 安全) */
  @JvmStatic
  fun inboundStats(ctx: Context? = null): JSONObject {
    val d = db(ctx)
    val midnight = java.util.Calendar.getInstance().apply {
      set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
      set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val today = d.compileStatement("SELECT COUNT(*) FROM inbound WHERE ts>=?")
    today.bindLong(1, midnight)
    val failed = d.compileStatement("SELECT COUNT(*) FROM inbound WHERE status='failed'")
    return JSONObject()
      .put("total", d.compileStatement("SELECT COUNT(*) FROM inbound").simpleQueryForLong())
      .put("today", today.simpleQueryForLong())
      .put("failed", failed.simpleQueryForLong())
  }

  // ---- 统计(决策 API /stats) ----
  @JvmStatic
  fun stats(ctx: Context? = null): JSONObject {
    val d = db(ctx)
    val total = d.compileStatement("SELECT COUNT(*) FROM decisions WHERE type='decision'").simpleQueryForLong()
    val st = d.compileStatement("SELECT COUNT(*) FROM decisions WHERE type='decision' AND date=?")
    st.bindString(1, localDate(System.currentTimeMillis()))
    val today = st.simpleQueryForLong()
    return JSONObject().put("total", total).put("today", today).put("db", "sqlite")
  }

  // ---- 读取(决策日志查询;行转旧 JSONL entry 形状,answers 回填为 decoded 键 —— 下游解析零改动) ----
  /** 旧 JSONL entry 等价行:{ts, task, state, decoded, latencyMs}(type='decision') */
  @JvmStatic
  fun queryDecisions(ctx: Context?, task: String? = null, fromTs: Long = 0, limit: Int = 0, orderDesc: Boolean = false): List<JSONObject> {
    val conds = ArrayList<String>().apply { add("type='decision'") }
    val args = ArrayList<String>()
    if (!task.isNullOrBlank()) { conds.add("task=?"); args.add(task) }
    if (fromTs > 0) { conds.add("ts>=?"); args.add(fromTs.toString()) }
    val sql = "SELECT ts,task,state,answers,latency_ms FROM decisions WHERE " + conds.joinToString(" AND ") +
        " ORDER BY ts " + (if (orderDesc) "DESC" else "ASC") + (if (limit > 0) " LIMIT $limit" else "")
    val out = ArrayList<JSONObject>()
    db(ctx).rawQuery(sql, args.toTypedArray()).use { c ->
      while (c.moveToNext()) {
        out.add(JSONObject()
          .put("ts", c.getLong(0))
          .put("task", c.getString(1))
          .put("state", c.getString(2))
          .put("decoded", JSONObject(c.getString(3)))
          .put("latencyMs", c.getLong(4)))
      }
    }
    return out
  }

  /** 最近一条 LLM 升级行(业务维度);行形状对齐旧 JSONL {ts,type,task,latencyMs,llm|error} */
  @JvmStatic
  fun lastLlm(ctx: Context?, task: String): JSONObject? {
    db(ctx).rawQuery("SELECT ts,latency_ms,llm FROM decisions WHERE type='llm' AND task=? ORDER BY ts DESC LIMIT 1", arrayOf(task)).use { c ->
      if (c.moveToFirst()) {
        val o = JSONObject().put("ts", c.getLong(0)).put("type", "llm").put("task", task).put("latencyMs", c.getLong(1))
        val llm = c.getString(2)
        if (llm != null) {
          val j = JSONObject(llm)
          if (j.has("error")) o.put("error", j.optString("error")) else o.put("llm", j)
        }
        return o
      }
    }
    return null
  }

  /** 全部行(决策 + llm,旧 JSONL 全量读取等价;detail/报表页用):
   *  决策行 {ts,task,state,decoded,latencyMs};llm 行 {ts,type,task,latencyMs,llm|error}。按 ts 升序。 */
  @JvmStatic
  fun queryAll(ctx: Context?): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    db(ctx).rawQuery("SELECT ts,task,type,state,answers,latency_ms,llm FROM decisions ORDER BY ts ASC", null as Array<String>?).use { c ->
      while (c.moveToNext()) {
        val o = JSONObject().put("ts", c.getLong(0)).put("task", c.getString(1))
        val type = c.getString(2)
        if (type == "llm") {
          o.put("type", "llm").put("latencyMs", c.getLong(5))
          val llm = c.getString(6)
          if (llm != null) {
            val j = JSONObject(llm)
            if (j.has("error")) o.put("error", j.optString("error")) else o.put("llm", j)
          }
        } else {
          o.put("state", c.getString(3)).put("decoded", JSONObject(c.getString(4))).put("latencyMs", c.getLong(5))
        }
        out.add(o)
      }
    }
    return out
  }

  // ---- 采集配置(collect_configs + meta['collect.intervalSec'];原 cfg.collect 迁入) ----

  /** prefs "gateway"/cfg JSON 的 collect 一次性迁移:拆 intervalSec + 各 task 写入,meta 标记防重;旧 prefs 键不删不再读 */
  private fun migrateCollect(ctx: Context) {
    try {
      val d = helper!!.writableDatabase
      if (metaLong(d, "migrated_collect") > 0) return
      val collect = JSONObject(ctx.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        .getString("cfg", "{}") ?: "{}").optJSONObject("collect") ?: return
      d.beginTransaction()
      try {
        val interval = collect.optLong("intervalSec", 0)
        if (interval > 0) d.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('collect.intervalSec',?)", arrayOf(interval.toString()))
        val now = System.currentTimeMillis()
        for (k in collect.keys()) {
          if (k == "intervalSec") continue
          val c1 = collect.optJSONObject(k) ?: continue
          val v = ContentValues().apply {
            put("task", k); put("enabled", if (c1.optBoolean("enabled")) 1 else 0)
            put("samples", c1.optJSONArray("samples")?.toString() ?: "[]"); put("updated_at", now)
          }
          d.insertWithOnConflict("collect_configs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
        d.setTransactionSuccessful()
      } finally { d.endTransaction() }
      d.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('migrated_collect','1')")
    } catch (_: Throwable) {}
  }

  /** 采集配置等价读出(旧 cfg.collect 形状 {"intervalSec", "<task>":{enabled,samples}}) */
  @JvmStatic
  fun collectAll(ctx: Context? = null): JSONObject {
    val out = JSONObject()
    db(ctx).rawQuery("SELECT task,enabled,samples FROM collect_configs ORDER BY task", null as Array<String>?).use { c ->
      while (c.moveToNext()) {
        out.put(c.getString(0), JSONObject().put("enabled", c.getInt(1) == 1).put("samples", JSONArray(c.getString(2))))
      }
    }
    return out.put("intervalSec", metaLong(db(ctx), "collect.intervalSec"))
  }

  @JvmStatic fun collectIntervalSec(ctx: Context? = null): Long = collectAll(ctx).optLong("intervalSec", 0)

  @JvmStatic
  fun collectSetTask(ctx: Context?, task: String, enabled: Boolean, samples: JSONArray) {
    val v = ContentValues().apply {
      put("task", task); put("enabled", if (enabled) 1 else 0)
      put("samples", samples.toString()); put("updated_at", System.currentTimeMillis())
    }
    db(ctx).insertWithOnConflict("collect_configs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
  }

  @JvmStatic
  fun collectRemove(ctx: Context?, task: String) {
    db(ctx).delete("collect_configs", "task=?", arrayOf(task))
  }

  @JvmStatic
  fun collectSetInterval(ctx: Context?, sec: Long) {
    if (sec >= 15) db(ctx).execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('collect.intervalSec',?)", arrayOf(sec.toString()))
  }

  // ---- 回复模板覆盖(templates 表;删行=恢复内置默认) ----
  private val tplCache = java.util.concurrent.ConcurrentHashMap<String, String>() // "task|locale|kind|key" -> body

  @JvmStatic
  fun templateGet(task: String, locale: String, kind: String, key: String): String? =
    tplCache["$task|$locale|$kind|$key"] ?: run {
      db().rawQuery("SELECT body FROM templates WHERE task=? AND locale=? AND kind=? AND key=?", arrayOf(task, locale, kind, key)).use { c ->
        if (c.moveToFirst()) { val b = c.getString(0); tplCache["$task|$locale|$kind|$key"] = b; b } else null
      }
    }

  @JvmStatic
  fun templateUpsert(ctx: Context?, task: String, locale: String, kind: String, key: String, body: String) {
    val v = ContentValues().apply {
      put("task", task); put("locale", locale); put("kind", kind); put("key", key)
      put("body", body); put("updated_at", System.currentTimeMillis())
    }
    db(ctx).insertWithOnConflict("templates", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    tplCache["$task|$locale|$kind|$key"] = body
  }

  /** 删覆盖行(恢复内置默认) */
  @JvmStatic
  fun templateDelete(ctx: Context?, rowid: Long): Int {
    db(ctx).rawQuery("SELECT task,locale,kind,key FROM templates WHERE rowid=?", arrayOf(rowid.toString())).use { c ->
      if (c.moveToFirst()) tplCache.remove("${c.getString(0)}|${c.getString(1)}|${c.getString(2)}|${c.getString(3)}")
    }
    return db(ctx).delete("templates", "rowid=?", arrayOf(rowid.toString()))
  }

  /** 覆盖行列表:{id,task,locale,kind,key,body}(task 非空时过滤) */
  @JvmStatic
  fun templateList(ctx: Context?, task: String? = null, locale: String? = null): JSONArray {
    val out = JSONArray()
    val conds = ArrayList<String>(); val args = ArrayList<String>()
    if (!task.isNullOrBlank()) { conds.add("task=?"); args.add(task) }
    if (!locale.isNullOrBlank()) { conds.add("locale=?"); args.add(locale) }
    val sql = "SELECT rowid,task,locale,kind,key,body FROM templates" +
        (if (conds.isEmpty()) "" else " WHERE " + conds.joinToString(" AND ")) + " ORDER BY kind,key,locale"
    db(ctx).rawQuery(sql, args.toTypedArray()).use { c ->
      while (c.moveToNext()) out.put(JSONObject()
        .put("id", c.getLong(0)).put("task", c.getString(1)).put("locale", c.getString(2))
        .put("kind", c.getString(3)).put("key", c.getString(4)).put("body", c.getString(5)))
    }
    return out
  }
}
