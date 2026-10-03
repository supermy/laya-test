package com.laya

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Date
import java.util.Locale

/** 全局决策核心:单引擎策略 + 问题定义 + 决策日志 + 报表。UI 与网关共用。 */
object DecisionCore {
  private val BUILTIN = linkedMapOf(
    "ticket" to "客服工单分流", "ugc" to "UGC 内容审核",
    "agent" to "Agent 工作流路由", "risk" to "金融风控前置",
  )

  /** 已安装业务:/sdcard/models 下 laya-litert-* 目录的 phone 子目录含全套模型文件;内置四业务 + 动态包 */
  @JvmStatic
  @JvmOverloads
  fun scanTasks(sdRoot: String = "/sdcard/models"): List<Pair<String, String>> {
    val out = LinkedHashMap<String, String>()
    val root = File(sdRoot)
    val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("laya-litert-") } ?: emptyArray()
    for (d in dirs) {
      val task = d.name.removePrefix("laya-litert-")
      val pkg = File(d, "phone")
      val ready = pkg.isDirectory && File(pkg, "laya_ml_s256_embeds_wfp16.tflite").isFile
      if (ready) out[task] = BUILTIN[task] ?: File(pkg, "label.txt").takeIf { it.isFile }?.readText()?.trim() ?: task
    }
    return out.toList()
  }

  private var jniEngine: DecisionEngine? = null
  private var runnerEngine: DecisionEngine? = null
  private var engineTask: String? = null

  private fun q(type: String, ins: String, criteria: Any? = null): Map<String, Any?> =
    LinkedHashMap<String, Any?>().apply {
      put("type", type); put("instructions", ins)
      if (criteria != null) put("criteria", criteria)
    }

  private fun cl(vararg kv: String): Map<String, Any?> =
    LinkedHashMap<String, Any?>().apply { kv.forEach { put(it, null) } }

  fun questionDefs(task: String): List<Pair<String, Map<String, Any?>>> {
    if (task !in BUILTIN) return externalDefs(task)
    return builtinDefs(task)
  }

  /** 动态业务:从 /sdcard/models/laya-litert-<task>/phone/questions.json 读问题定义 */
  private fun externalDefs(task: String): List<Pair<String, Map<String, Any?>>> {
    val f = File("/sdcard/models/laya-litert-$task/phone/questions.json")
    val out = ArrayList<Pair<String, Map<String, Any?>>>()
    val arr = org.json.JSONArray(f.readText())
    for (i in 0 until arr.length()) {
      val q = arr.getJSONObject(i)
      val m = LinkedHashMap<String, Any?>()
      m["type"] = q.getString("type")
      m["instructions"] = q.optString("instructions")
      val crit = q.opt("criteria")
      if (crit != null) m["criteria"] = if (crit is org.json.JSONArray) {
        val l = ArrayList<Any?>()
        for (j in 0 until crit.length()) l.add(crit.getString(j))
        l
      } else crit
      out.add(q.optString("qid", "q$i") to m)
    }
    return out
  }

  private fun builtinDefs(task: String): List<Pair<String, Map<String, Any?>>> = when (task) {
    "ticket" -> listOf(
      "q0" to q("choice", "分诊到售后部门", cl("技术支持", "账单计费", "销售咨询", "退换货", "故障维护", "其他")),
      "q1" to q("score", "客户紧急度1-5", listOf("可忽略", "低", "中", "高", "紧急")),
      "q2" to q("noul", "是否必须转人工处理(自动回复无法解决)"),
    )
    "ugc" -> listOf(
      "q0" to q("choice", "审核判定", cl("正常", "广告导流", "辱骂攻击", "色情低俗", "诈骗引流")),
      "q1" to q("score", "违规严重度1-5", listOf("无违规", "轻微", "一般", "较重", "严重")),
      "q2" to q("noul", "是否需要人工复核(机器置信不足或处置风险高)"),
    )
    "agent" -> listOf(
      "q0" to q("choice", "路由到工作流", cl("直接回答", "检索问答", "工具调用", "多步规划", "转人工")),
      "q1" to q("score", "任务复杂度1-5", listOf("一句话", "简单", "中等", "复杂", "极复杂")),
      "q2" to q("noul", "是否涉及多实体或多约束需要任务拆解"),
    )
    else -> listOf(
      "q0" to q("score", "风险信号强度1-5", listOf("无信号", "弱", "中", "强", "极强")),
      "q1" to q("choice", "主要风险类型", cl("无风险信号", "信用风险", "欺诈风险", "合规风险")),
      "q2" to q("noul", "是否命中强风控信号建议人工复核"),
    )
  }

  @Synchronized
  private fun ensureRunner(ctx: Context, task: String): DecisionEngine {
    if (runnerEngine != null && engineTask == task) return runnerEngine!!
    jniEngine?.let { try { it.close() } catch (_: Throwable) {} }
    jniEngine = null
    val base = File(ctx.filesDir, "laya-$task").apply { mkdirs() }
    val src = File("/sdcard/models/laya-litert-$task/phone")
    val files = listOf(
      "laya_ml_s256_embeds_wfp16.tflite", "laya_ml_act_head_fp32.tflite",
      "token_embeddings_fp16.bin", "token_embeddings.json",
      "laya_ml_calibration.json", "tokenizer.json",
    )
    for (f in files) {
      val s = File(src, f)
      check(s.isFile) { "缺少模型文件 $s(先在系统页安装模型)" }
      val d = File(base, f)
      if (!d.isFile || d.length() != s.length()) s.copyTo(d, overwrite = true)
    }
    val e = try {
      // 首选独立进程 runner(实测 17ms/问,绕开主进程 CL 回读慢路径)
      LayaRunnerEngine(ctx, base)
    } catch (t: Throwable) {
      Log.w("DecisionCore", "runner failed, fallback JNI GPU/CPU", t)
      LayaNativeEngine(ctx, base, true)
    }
    runnerEngine = e; engineTask = task
    return e
  }

  /**
   * 手动导入模型包:source 为含 6 个模型文件的目录,或 .zip 压缩包。
   * 复制到 /sdcard/models/laya-litert-<task>/phone/,成功返回 null,失败返回错误信息。
   */
  @JvmStatic
  fun importPackage(ctx: Context, source: String, task: String): String? {
    if (!task.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) return "业务名只允许字母/数字/下划线/中划线"
    if (!source.lowercase().endsWith(".zip") && !File(source).isDirectory) return "源不存在或不是目录/zip: $source"
    val required = listOf(
      "laya_ml_s256_embeds_wfp16.tflite", "laya_ml_act_head_fp32.tflite",
      "token_embeddings_fp16.bin", "token_embeddings.json",
      "laya_ml_calibration.json", "tokenizer.json",
    )
    val tmp: File
    if (source.lowercase().endsWith(".zip")) {
      tmp = File(ctx.cacheDir, "laya-import-$task").apply { deleteRecursively() }
      try {
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(source))).use { zis ->
          var e = zis.nextEntry
          val outRoot = tmp.canonicalPath + "/"
          while (e != null) {
            val f = File(tmp, e.name)
            if (!f.canonicalPath.startsWith(outRoot)) return "zip 内含非法路径: ${e.name}"
            if (e.isDirectory) f.mkdirs()
            else {
              f.parentFile?.mkdirs()
              java.io.FileOutputStream(f).use { zis.copyTo(it) }
            }
            e = zis.nextEntry
          }
        }
      } catch (t: Throwable) { tmp.deleteRecursively(); return "zip 解压失败: ${t.message}" }
    } else tmp = File(source)
    val missing = required.filterNot { File(tmp, it).isFile() }
    if (missing.isNotEmpty()) { if (source.lowercase().endsWith(".zip")) tmp.deleteRecursively(); return "缺文件: $missing" }
    // 命名/内容检测:questions.json(若携带)必须是 JSON 数组
    val qf = File(tmp, "questions.json")
    if (qf.isFile) {
      try { org.json.JSONArray(qf.readText()) } catch (t: Throwable) {
        if (source.lowercase().endsWith(".zip")) tmp.deleteRecursively()
        return "questions.json 不是合法 JSON 数组: ${t.message}"
      }
    }
    val dst = File("/sdcard/models/laya-litert-$task/phone").apply { mkdirs() }
    try {
      for (f in tmp.listFiles() ?: emptyArray()) {
        if (f.isFile) f.copyTo(File(dst, f.name), overwrite = true)
      }
    } catch (t: Throwable) { return "复制失败: ${t.message}" }
    if (source.lowercase().endsWith(".zip")) tmp.deleteRecursively()
    return null
  }

  /** 预装业务(拷模型 + 引擎初始化;耗时操作,勿在 UI 线程调用) */
  @JvmStatic
  @Synchronized
  fun preload(ctx: Context, task: String) { ensureRunner(ctx, task) }

  private fun unloadEngineOnly() {
    runnerEngine?.let { try { it.close() } catch (_: Throwable) {} }
    runnerEngine = null
    jniEngine?.let { try { it.close() } catch (_: Throwable) {} }
    jniEngine = null
    engineTask = null
  }

  /** 卸载业务:释放引擎(若是当前)并删除已拷贝的模型文件(/sdcard 源包保留,可重装) */
  @JvmStatic
  @Synchronized
  fun unload(ctx: Context, task: String) {
    if (engineTask == task) {
      runnerEngine?.let { try { it.close() } catch (_: Throwable) {} }
      runnerEngine = null
      jniEngine?.let { try { it.close() } catch (_: Throwable) {} }
      jniEngine = null
      engineTask = null
    }
    File(ctx.filesDir, "laya-$task").deleteRecursively()
  }

  class Result(@JvmField val answers: JSONObject, @JvmField val latencyMs: Long)

  /** 三问全跑。线程安全(引擎内部单线程,此处 synchronized 防并发切换)。 */
  @Synchronized
  @JvmStatic
  fun decide(ctx: Context, task: String, text: String): Result {
    val eng = ensureRunner(ctx, task)
    val t0 = System.nanoTime()
    val answers = JSONObject()
    for ((qid, qdef) in questionDefs(task)) {
      answers.put(qid, JSONObject(eng.answer(text, qdef, qid)))
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    appendLog(ctx, task, text, answers, ms)
    return Result(answers, ms)
  }

  // ---- 决策日志 ----
  @JvmStatic
  fun logFile(ctx: Context): File {
    val ym = SimpleDateFormat("yyyyMM", Locale.US)
    return File(ctx.getExternalFilesDir(null), "decisions-" + ym.format(Date()) + ".jsonl")
  }

  @JvmStatic
  fun appendLog(ctx: Context, task: String, text: String, decoded: JSONObject, ms: Long) {
    try {
      val o = JSONObject()
      o.put("ts", System.currentTimeMillis())
      o.put("task", task)
      o.put("state", if (text.length > 200) text.substring(0, 200) else text)
      o.put("decoded", decoded)
      o.put("latencyMs", ms)
      java.io.FileOutputStream(logFile(ctx), true).use { it.write((o.toString() + "\n").toByteArray()) }
    } catch (_: Exception) {}
  }

  /** 某业务的历史决策(当前月日志,按时间正序,最多 limit 条) */
  @JvmStatic
  fun history(ctx: Context, task: String, limit: Int = 30): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    val f = logFile(ctx)
    if (!f.isFile) return out
    for (line in java.nio.file.Files.readAllBytes(f.toPath()).toString(Charsets.UTF_8).split("\n")) {
      if (line.isBlank()) continue
      try { val o = JSONObject(line); if (o.optString("task") == task) out.add(o) } catch (_: Exception) {}
    }
    return if (out.size > limit) out.subList(out.size - limit, out.size) else out
  }

  /** 详单分页:返回 {text, page(1基), pages} */
  @JvmStatic
  fun detail(ctx: Context, page: Int, pageSize: Int = 20): JSONObject {
    val f = logFile(ctx)
    val all = ArrayList<JSONObject>()
    if (f.isFile) {
      for (line in java.nio.file.Files.readAllBytes(f.toPath()).toString(Charsets.UTF_8).split("\n")) {
        if (line.isBlank()) continue
        try { all.add(JSONObject(line)) } catch (_: Exception) {}
      }
    }
    val pages = Math.max(1, Math.ceil(all.size / pageSize.toDouble()).toInt())
    val p = page.coerceIn(0, pages - 1)
    val slice = all.drop(p * pageSize).take(pageSize)
    val df = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    val sb = StringBuilder("== 详单(共 ").append(all.size).append(" 条 · 第 ").append(p + 1).append("/").append(pages).append(" 页)==\n\n")
    if (slice.isEmpty()) sb.append("(本页无数据)\n")
    for (e in slice) {
      val st = e.optString("state")
      sb.append("• ").append(df.format(Date(e.optLong("ts")))).append(" [").append(e.optString("task")).append("] ")
        .append(st, 0, minOf(48, st.length))
      // 处理结果:各问答案摘要
      val dec = e.optJSONObject("decoded")
      if (dec != null) {
        val parts = ArrayList<String>()
        val kit = dec.keys()
        while (kit.hasNext()) {
          val a = dec.optJSONObject(kit.next()) ?: continue
          when (a.optString("type")) {
            "choice" -> parts.add(a.optString("choice"))
            "score" -> parts.add(String.format(Locale.US, "%.1f分", a.optDouble("score")))
            "noul" -> parts.add(if (a.optDouble("noul") >= 0.5) "需人工" else "自动")
          }
        }
        if (parts.isNotEmpty()) sb.append(" → ").append(parts.joinToString(" | "))
      }
      sb.append("\n")
    }
    val o = JSONObject()
    o.put("text", sb.toString()); o.put("page", p + 1); o.put("pages", pages)
    return o
  }

  /** 决策结果 → 可读文本(邮件回复/MQTT 消息共用) */
  @JvmStatic
  fun fmt(task: String, answers: JSONObject): String {
    val label = BUILTIN[task] ?: scanTasks().firstOrNull { it.first == task }?.second ?: task
    val sb = StringBuilder("【").append(label).append("】\n")
    for ((qid, def) in questionDefs(task)) {
      val a = answers.optJSONObject(qid) ?: continue
      when (a.optString("type")) {
        "choice" -> sb.append("• ").append(def["instructions"]).append(": ").append(a.optString("choice"))
          .append(" (").append(a.optDouble("confidence")).append(")\n")
        "score" -> sb.append("• ").append(def["instructions"]).append(": ").append(a.optDouble("score"))
          .append("/5\n")
        "noul" -> sb.append("• ").append(def["instructions"]).append(": ")
          .append(if (a.optDouble("noul") >= 0.5) "是" else "否").append("\n")
      }
    }
    return sb.toString()
  }

  // ---- 报表(日报 0 / 月报 1 / 年报 2 / 详单 3) ----
  @JvmStatic
  fun report(ctx: Context, kind: Int): String {
    val f = logFile(ctx)
    val all = ArrayList<JSONObject>()
    if (f.isFile()) {
      for (line in java.nio.file.Files.readAllBytes(f.toPath()).toString(Charsets.UTF_8).split("\n")) {
        if (line.isBlank()) continue
        try { all.add(JSONObject(line)) } catch (_: Exception) {}
      }
    }
    val names = arrayOf("日报", "月报", "年报", "详单")
    val df = if (kind == 2) SimpleDateFormat("yyyy-MM", Locale.US) else SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val buckets = LinkedHashMap<String, MutableList<JSONObject>>()
    for (e in all) {
      val c = Calendar.getInstance().apply {
        timeInMillis = e.optLong("ts")
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (kind == 1) set(Calendar.DAY_OF_MONTH, 1)
        if (kind == 2) set(Calendar.DAY_OF_YEAR, 1)
      }
      buckets.computeIfAbsent(df.format(c.time)) { ArrayList() }.add(e)
    }
    val sb = StringBuilder("== ").append(names[kind]).append("(共 ").append(all.size).append(" 条决策)==\n\n")
    for ((key, list) in buckets) {
      var lat = 0L
      val labels = LinkedHashMap<String, Int>()
      for (e in list) {
        lat += e.optLong("latencyMs")
        val dec = e.optJSONObject("decoded") ?: continue
        val it = dec.keys()
        while (it.hasNext()) {
          val a = dec.optJSONObject(it.next()) ?: continue
          if ("choice" == a.optString("type")) {
            val k = a.optString("choice")
            labels[k] = (labels[k] ?: 0) + 1
          }
        }
      }
      sb.append("【").append(key).append("】").append(list.size).append(" 条,平均 ")
        .append(if (list.isEmpty()) 0 else lat / list.size).append("ms\n")
      for ((k, v) in labels) sb.append("   ").append(k).append(": ").append(v).append("\n")
      sb.append("\n")
      if (kind == 0) {
        val df = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        for (e in list) {
          val st = e.optString("state")
          sb.append("  ").append(df.format(Date(e.optLong("ts")))).append(" [").append(e.optString("task")).append("] ")
            .append(st, 0, minOf(40, st.length)).append("\n")
        }
      }
    }
    return sb.toString()
  }
}
