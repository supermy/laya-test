package com.laya

import android.content.Context
import android.content.Intent
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.Properties
import java.util.UUID
import javax.mail.Authenticator
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm

/**
 * 内置网关(Termux 通道的生产替代):
 *  - 邮件:IMAP 轮询未读邮件,识别指令 "laya <ticket|ugc|agent|risk> <文本>" → 决策 → SMTP 回复;
 *    配置了 report.to 则每日推送日报
 *  - MQTT:订阅 sub 主题(JSON {"task","text"})→ 决策 → 发布 pub 主题(JSON 含 answers/latencyMs)
 * 配置存 SharedPreferences;通过 GatewayService 前台保活,开机自启。
 */
object Gateway {
  private const val TAG = "Gateway"
  private const val PREF = "gateway"
  private val CMD_RE = Regex("(?i)laya\\s+(ticket|ugc|agent|risk)\\s+([\\s\\S]+)")

  @Volatile private var emailRunning = false
  @Volatile private var mqttRunning = false
  private var mqtt: MqttClient? = null

  /** 模型包上传服务(局域网),随网关启停 */
  @JvmStatic
  fun uploadRunning(): Boolean = UploadServer.running()

  @JvmStatic
  fun uploadUrl(): String = UploadServer.url()

  @JvmStatic
  fun cfg(ctx: Context): JSONObject =
    JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(Cfg.DOC, "{}") ?: "{}")

  /** 仅保存配置(不启停);供 UI 细粒度开关使用 */
  @JvmStatic
  fun saveCfg(ctx: Context, cfg: JSONObject) {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(Cfg.DOC, cfg.toString()).apply()
  }

  @JvmStatic
  fun saveAndStart(ctx: Context, cfg: JSONObject): String {
    cfg.put(Cfg.ENABLED, true)
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(Cfg.DOC, cfg.toString()).apply()
    start(ctx)
    ctx.startForegroundService(Intent(ctx, GatewayService::class.java))
    return status(ctx)
  }

  @JvmStatic
  fun stop(ctx: Context): String {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
      .putString(Cfg.DOC, cfg(ctx).put(Cfg.ENABLED, false).toString()).apply()
    emailRunning = false
    try { mqtt?.disconnect() } catch (_: Exception) {}
    mqtt = null; mqttRunning = false
    UploadServer.stopServer()
    DecisionApiServer.stopServer()
    stopCollect()
    ctx.stopService(Intent(ctx, GatewayService::class.java))
    return ctx.getString(com.selfhost.layatest.R.string.gw_stopped)
  }

  // ---- LLM 设置已随决策链下线移除(v1.4.50):决策以本地模型输出 + 回复模板收尾 ----

  // ---- 全业务数据采集(网关自动采集:按模型包启停,样本轮询送决策,写决策日志) ----
  // 采集方式按业务的现有数据源决定:无实时源的业务(工单/UGC/Agent/风控/多语言)用本调度器轮询样本;
  // 有实时源的业务(资讯)由 NewsPage/NewsAutoService 直接拉流,采集开关走 cfg.news.<source>.enabled,不在此轮询。
  private val collectHandler = android.os.Handler(android.os.Looper.getMainLooper())
  @Volatile private var collectRunning = false
  @Volatile private var collectBusy = false
  private val collectIdx = HashMap<String, Int>()

  /** cfg.collect = {"intervalSec":60, "<task>":{"enabled":true,"samples":["..."]}} */
  private fun collectEnabledCount(c: JSONObject): Int {
    val cc = c.optJSONObject("collect") ?: return 0
    var n = 0
    for (k in cc.keys()) if (k != "intervalSec" && cc.optJSONObject(k)?.optBoolean("enabled") == true) n++
    return n
  }

  @JvmStatic fun startCollect(ctx: Context) {
    // 采集随网关启停:网关未启用时不启动样本轮询。
    // 否则「保存采集配置」经 restartCollect 会在网关关闭状态下静默起采集(状态栏还不显示)。
    if (!cfg(ctx).optBoolean(Cfg.ENABLED)) { Log.i(TAG, "collect idle: gateway disabled"); return }
    if (collectRunning) return
    collectRunning = true
    scheduleNext(ctx.getApplicationContext())
    Log.i(TAG, "collect scheduler up")
  }

  @JvmStatic fun stopCollect() {
    collectRunning = false
    collectHandler.removeCallbacksAndMessages(null)
    Log.i(TAG, "collect scheduler stopped")
  }

  /** 保存采集配置后重载调度(间隔/样本生效,无需重启网关) */
  @JvmStatic fun restartCollect(ctx: Context) {
    stopCollect()
    startCollect(ctx)
  }

  private fun scheduleNext(ctx: Context) {
    if (!collectRunning) return
    val intervalSec = (cfg(ctx).optJSONObject("collect")?.optLong("intervalSec", 60) ?: 60).coerceAtLeast(15)
    collectHandler.postDelayed({
      // 上一轮线程未收尾则跳过本轮,避免 decide 慢于 interval 时线程堆积在串行锁上
      if (collectBusy) Log.i(TAG, "collect skip: previous round still running")
      else try { runCollectOnce(ctx) } catch (_: Exception) {}
      scheduleNext(ctx)
    }, intervalSec * 1000)
  }

  /** 一轮采集:每个启用业务取一条样本(轮询)→ decide(写决策日志,回复模板随决策产出) */
  @JvmStatic fun runCollectOnce(ctx: Context) {
    val c = cfg(ctx)
    if (collectEnabledCount(c) == 0) return
    val cc = c.optJSONObject("collect") ?: return
    val app = ctx.applicationContext
    collectBusy = true
    Thread {
      try {
        // 清理已删除业务的轮询游标(cfg 中不再存在的键)
        collectIdx.keys.retainAll(cc.keys().asSequence().filter { it != "intervalSec" }.toHashSet())
        for (k in cc.keys()) {
          if (k == "intervalSec") continue
          val c1 = cc.optJSONObject(k) ?: continue
          if (!c1.optBoolean("enabled")) continue
          val samples = c1.optJSONArray("samples") ?: continue
          if (samples.length() == 0) continue
          val i = (collectIdx[k] ?: 0) % samples.length()
          collectIdx[k] = i + 1
          try {
            DecisionCore.decide(app, k, samples.optString(i)) // 决策+日志+模板全链;串行锁内排队,勿在此打印大对象
          } catch (e: Exception) { Log.w(TAG, "collect $k: ${e.message}") }
        }
      } finally { collectBusy = false }
    }.start()
  }

  /** 当前选中的 LLM 槽位(含 id),未配置返回 null */
  @JvmStatic
  fun llmActive(ctx: Context): JSONObject? {
    val llm = cfg(ctx).optJSONObject(Cfg.LLM) ?: return null
    val id = llm.optString(Cfg.ACTIVE).ifBlank { "llm1" }
    val s = llm.optJSONObject(Cfg.SLOTS)?.optJSONObject(id) ?: return null
    return s.put("id", id)
  }

  @JvmStatic
  fun status(ctx: Context): String {
    val c = cfg(ctx)
    if (!c.optBoolean(Cfg.ENABLED)) return ctx.getString(com.selfhost.layatest.R.string.gw_disabled)
    val parts = ArrayList<String>()
    if (c.optJSONObject(Cfg.EMAIL)?.optBoolean(Cfg.ENABLED) == true) parts.add(ctx.getString(com.selfhost.layatest.R.string.mail_running))
    if (c.optJSONObject(Cfg.MQTT)?.optBoolean(Cfg.ENABLED) == true) parts.add(ctx.getString(com.selfhost.layatest.R.string.mqtt_connected, c.optJSONObject(Cfg.MQTT)?.optString(Cfg.URL)))
    if (UploadServer.running()) parts.add(ctx.getString(com.selfhost.layatest.R.string.upload_running, UploadServer.url()))
    if (DecisionApiServer.running()) parts.add(ctx.getString(com.selfhost.layatest.R.string.api_running, DecisionApiServer.url()))
    val nCollect = collectEnabledCount(c)
    if (nCollect > 0) parts.add(ctx.getString(com.selfhost.layatest.R.string.collect_running, nCollect))
    return if (parts.isEmpty()) ctx.getString(com.selfhost.layatest.R.string.gw_enabled_no_ch) else parts.joinToString(";")
  }

  /** app 启动时恢复(仅 enabled 时拉起) */
  @JvmStatic
  fun autoStart(ctx: Context) {
    if (cfg(ctx).optBoolean(Cfg.ENABLED)) start(ctx)
  }

  @Synchronized
  fun start(ctx: Context) {
    UploadServer.start(ctx) // 上传服务随网关常驻(局域网页面/接口)
    val c = cfg(ctx)
    val api = c.optJSONObject(Cfg.API)
    if (api?.optBoolean(Cfg.ENABLED) == true) DecisionApiServer.start(ctx, api.optInt(Cfg.PORT, DecisionApiServer.PORT), /*ensureFgs=*/false)
    val email = c.optJSONObject(Cfg.EMAIL)
    if (email?.optBoolean(Cfg.ENABLED) == true && !emailRunning) startEmail(ctx, email, c.optJSONObject(Cfg.REPORT))
    val mq = c.optJSONObject(Cfg.MQTT)
    if (mq?.optBoolean(Cfg.ENABLED) == true && !mqttRunning) startMqtt(ctx, mq, c.optJSONObject(Cfg.TOPICS))
    startCollect(ctx) // 全业务数据采集调度(未启用业务自动跳过)
  }

  // ---- 邮件网关 ----
  private fun startEmail(ctx: Context, email: JSONObject, report: JSONObject?) {
    emailRunning = true
    Thread {
      var lastReportDay = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(Cfg.LAST_REPORT_DAY, "") ?: ""
      while (emailRunning) {
        try {
          pollEmail(ctx, email)
        } catch (t: Throwable) {
          Log.w(TAG, "email poll: ${t.message}")
        }
        try {
          val to = report?.optString(Cfg.TO) ?: ""
          val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
          if (to.isNotEmpty() && day != lastReportDay) {
            sendMail(email, to, ctx.getString(com.selfhost.layatest.R.string.mail_daily_subject, day), DecisionCore.report(ctx, 0))
            lastReportDay = day
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(Cfg.LAST_REPORT_DAY, day).apply()
            Log.i(TAG, "daily report sent to $to")
          }
        } catch (t: Throwable) {
          Log.w(TAG, "daily report: ${t.message}")
        }
        try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@Thread }
      }
    }.apply { isDaemon = true; name = "Gateway-Email"; start() }
  }

  private fun pollEmail(ctx: Context, email: JSONObject) {
    val host = email.optString(Cfg.HOST); val user = email.optString(Cfg.USER); val pass = email.optString(Cfg.PASS)
    if (host.isEmpty() || user.isEmpty()) return
    val ssl = email.optBoolean(Cfg.SSL, true)
    val port = email.optInt("imapPort", if (ssl) 993 else 143)
    val props = Properties().apply {
      put("mail.store.protocol", if (ssl) "imaps" else "imap")
      // pymap 对 BODY[TEXT]<0.16384> partial 的响应 JavaMail 解析为空,关掉 partial
      put("mail.imap.partialfetch", "false")
      put("mail.imaps.partialfetch", "false")
    }
    val session = Session.getInstance(props, null)
    val store = session.getStore(if (ssl) "imaps" else "imap")
    store.connect(host, port, user, pass)
    val inbox = store.getFolder("INBOX")
    inbox.open(Folder.READ_WRITE)
    val unseen = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
    for (msg in unseen) {
      try {
        // pymap 的 BODYSTRUCTURE/BODY[TEXT] 响应与 JavaMail 高层 API 不兼容
        // (content 为空)——fetch 原始流后本地解析
        val body = (msg as MimeMessage).rawInputStream.readBytes().toString(Charsets.UTF_8)
        val hay = (msg.subject ?: "") + "\n" + body
        Log.i(TAG, "polled mail: subj=${msg.subject} bodyLen=${body.length} bodyHead=${body.take(60).replace("\n", " ")}")
        val m = CMD_RE.find(hay)
        if (m != null) {
          val task = m.groupValues[1]; val text = m.groupValues[2].trim().take(500)
          val r = DecisionCore.decide(ctx, task, text)
          val from = (msg.from.firstOrNull() as? InternetAddress)?.address
          if (from != null) sendMail(email, from,
            ctx.getString(com.selfhost.layatest.R.string.mail_reply_subject, msg.subject ?: ""),
            DecisionCore.fmt(ctx, task, r.answers) + "\n----\n" + DecisionCore.autoReply(ctx, task, r.answers))
          Log.i(TAG, "email decision $task ${r.latencyMs}ms")
        }
      } catch (t: Throwable) {
        Log.w(TAG, "handle mail: ${t.message}")
      }
      try { msg.setFlag(Flags.Flag.SEEN, true) } catch (_: Exception) {}
    }
    inbox.close(false)
    store.close()
  }

  private fun sendMail(email: JSONObject, to: String, subject: String, text: String) {
    val host = email.optString(Cfg.HOST); val user = email.optString(Cfg.USER); val pass = email.optString(Cfg.PASS)
    val useSsl = email.optBoolean(Cfg.SSL, true)
    val port = email.optInt("smtpPort", if (useSsl) 465 else 25)
    val props = Properties().apply {
      put("mail.smtp.auth", "true")
      put("mail.smtp.ssl.enable", useSsl.toString())
      put("mail.smtp.starttls.enable", "false")
      put("mail.smtp.host", host); put("mail.smtp.port", port.toString())
      if (useSsl) put("mail.smtp.ssl.trust", host)
    }
    val s = Session.getInstance(props, object : Authenticator() {
      override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
    })
    val m = MimeMessage(s)
    m.setFrom(InternetAddress(user))
    m.setRecipients(MimeMessage.RecipientType.TO, to)
    m.subject = subject
    m.setText(text, "UTF-8")
    Transport.send(m)
  }

  // ---- 手动测试(即时连通性验证,不改配置不启动常驻) ----

  /** IMAP 登录 + 打开收件箱,返回邮件数 */
  @JvmStatic
  fun testEmail(ctx: Context, email: JSONObject): String {
    return try {
      val host = email.optString(Cfg.HOST); val user = email.optString(Cfg.USER); val pass = email.optString(Cfg.PASS)
      if (host.isEmpty() || user.isEmpty()) return ctx.getString(com.selfhost.layatest.R.string.imap_fill_host)
      val ssl = email.optBoolean(Cfg.SSL, true)
      val port = email.optInt("imapPort", if (ssl) 993 else 143)
      val t0 = android.os.SystemClock.elapsedRealtime()
      val props = Properties().apply {
        put("mail.store.protocol", if (ssl) "imaps" else "imap")
        put("mail.imap.partialfetch", "false"); put("mail.imaps.partialfetch", "false")
      }
      val store = Session.getInstance(props, null).getStore(if (ssl) "imaps" else "imap")
      store.connect(host, port, user, pass)
      val inbox = store.getFolder("INBOX"); inbox.open(Folder.READ_ONLY)
      val n = inbox.messageCount
      inbox.close(false); store.close()
      ctx.getString(com.selfhost.layatest.R.string.imap_ok, host, port, n,
          (android.os.SystemClock.elapsedRealtime() - t0).toInt())
    } catch (t: Throwable) { ctx.getString(com.selfhost.layatest.R.string.err_imap, t.message ?: "") }
  }

  /** SMTP 发一封测试邮件 */
  @JvmStatic
  fun testSmtp(ctx: Context, email: JSONObject, to: String): String {
    return try {
      if (to.isEmpty()) return ctx.getString(com.selfhost.layatest.R.string.smtp_fill_to)
      val t0 = android.os.SystemClock.elapsedRealtime()
      sendMail(email, to, ctx.getString(com.selfhost.layatest.R.string.mail_test_subject),
          ctx.getString(com.selfhost.layatest.R.string.mail_test_body))
      ctx.getString(com.selfhost.layatest.R.string.smtp_ok, to,
          (android.os.SystemClock.elapsedRealtime() - t0).toInt())
    } catch (t: Throwable) { ctx.getString(com.selfhost.layatest.R.string.err_smtp, t.message ?: "") }
  }

  /** MQTT 连接 + 订阅 + 发布 ping */
  @JvmStatic
  fun testMqtt(ctx: Context, mq: JSONObject, topics: JSONObject?): String {
    return try {
      val url = mq.optString(Cfg.URL)
      if (url.isEmpty()) return ctx.getString(com.selfhost.layatest.R.string.mqtt_fill_url)
      val sub = topics?.optString(Cfg.SUB)?.ifEmpty { "laya/req/+" } ?: "laya/req/+"
      val pub = topics?.optString(Cfg.PUB)?.ifEmpty { "laya/resp" } ?: "laya/resp"
      val t0 = android.os.SystemClock.elapsedRealtime()
      val c = MqttClient(url, "laya-test-" + UUID.randomUUID().toString().take(6), MemoryPersistence())
      val opts = MqttConnectOptions().apply { isCleanSession = true; connectionTimeout = 8; keepAliveInterval = 30 }
      c.connect(opts)
      c.subscribe(sub)
      c.publish(pub, MqttMessage(JSONObject().put("task", "__test").put("text", "ping").toString().toByteArray()))
      c.disconnect()
      ctx.getString(com.selfhost.layatest.R.string.mqtt_ok, url, sub, pub,
          (android.os.SystemClock.elapsedRealtime() - t0).toInt())
    } catch (t: Throwable) { ctx.getString(com.selfhost.layatest.R.string.err_mqtt, t.message ?: "") }
  }

  /** 上传服务 HTTP 自探 */
  @JvmStatic
  fun testUpload(ctx: Context): String {
    return try {
      if (!UploadServer.running()) return ctx.getString(com.selfhost.layatest.R.string.upload_not_running)
      val u = uploadUrl()
      val t0 = android.os.SystemClock.elapsedRealtime()
      val c = (java.net.URL(u).openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 4000; readTimeout = 4000
      }
      val code = c.responseCode; c.disconnect()
      if (code < 400) ctx.getString(com.selfhost.layatest.R.string.upload_ok_msg, code, u,
          (android.os.SystemClock.elapsedRealtime() - t0).toInt())
      else ctx.getString(com.selfhost.layatest.R.string.upload_http_err, code, u)
    } catch (t: Throwable) { ctx.getString(com.selfhost.layatest.R.string.upload_err, t.message ?: "") }
  }

  // ---- MQTT 网关 ----
  private fun startMqtt(ctx: Context, mq: JSONObject, topics: JSONObject?) {
    mqttRunning = true
    Thread {
      try {
        val sub = topics?.optString(Cfg.SUB)?.ifEmpty { "laya/req/+" } ?: "laya/req/+"
        val pub = topics?.optString(Cfg.PUB)?.ifEmpty { "laya/resp" } ?: "laya/resp"
        val client = MqttClient(mq.optString(Cfg.URL), "laya-apk-" + UUID.randomUUID().toString().take(8), MemoryPersistence())
        client.setCallback(object : MqttCallbackExtended {
          override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            Log.i(TAG, "mqtt connected $serverURI")
            client.subscribe(sub)
          }
          override fun connectionLost(cause: Throwable?) { Log.w(TAG, "mqtt lost: ${cause?.message}") }
          override fun messageArrived(topic: String?, message: MqttMessage?) {
            try {
              if (message == null) return
              val req = JSONObject(message.toString())
              val task = req.optString("task")
              val text = req.optString("text")
              if (DecisionCore.scanTasks().any { it.first == task } && text.isNotEmpty()) {
                val r = DecisionCore.decide(ctx, task, text)
                val resp = JSONObject()
                resp.put("task", task); resp.put("text", text)
                resp.put("answers", r.answers); resp.put("latencyMs", r.latencyMs)
                resp.put("reply", DecisionCore.replyAppendix(ctx, task, r.answers, text))
                if (client.isConnected) client.publish(pub, MqttMessage(resp.toString().toByteArray()))
              }
            } catch (t: Throwable) { Log.w(TAG, "mqtt req: ${t.message}") }
          }
          override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply { isCleanSession = true; connectionTimeout = 10; keepAliveInterval = 30 }
        client.connect(opts)
        mqtt = client
        Log.i(TAG, "mqtt gateway up: sub=$sub pub=$pub")
      } catch (t: Throwable) {
        Log.w(TAG, "mqtt start: ${t.message}")
        mqttRunning = false
      }
    }.apply { isDaemon = true; name = "Gateway-MQTT"; start() }
  }
}
