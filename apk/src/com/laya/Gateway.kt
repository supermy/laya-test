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
    JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("cfg", "{}") ?: "{}")

  /** 仅保存配置(不启停);供 UI 细粒度开关使用 */
  @JvmStatic
  fun saveCfg(ctx: Context, cfg: JSONObject) {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("cfg", cfg.toString()).apply()
  }

  @JvmStatic
  fun saveAndStart(ctx: Context, cfg: JSONObject): String {
    cfg.put("enabled", true)
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("cfg", cfg.toString()).apply()
    start(ctx)
    ctx.startForegroundService(Intent(ctx, GatewayService::class.java))
    return status(ctx)
  }

  @JvmStatic
  fun stop(ctx: Context): String {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
      .putString("cfg", cfg(ctx).put("enabled", false).toString()).apply()
    emailRunning = false
    try { mqtt?.disconnect() } catch (_: Exception) {}
    mqtt = null; mqttRunning = false
    UploadServer.stopServer()
    DecisionApiServer.stopServer()
    ctx.stopService(Intent(ctx, GatewayService::class.java))
    return ctx.getString(com.selfhost.layatest.R.string.gw_stopped)
  }

  // ---- LLM 设置(重要+紧急升级通道,3 槽位供可选) ----
  @JvmStatic
  fun saveLlm(ctx: Context, llm: JSONObject): String {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
      .putString("cfg", cfg(ctx).put("llm", llm).toString()).apply()
    val a = llm.optJSONObject("slots")?.optJSONObject(llm.optString("active"))
    return ctx.getString(com.selfhost.layatest.R.string.llm_saved_prefix,
        a?.optString("name")?.ifBlank { null } ?: llm.optString("active", "?"))
  }

  /** 当前选中的 LLM 槽位(含 id),未配置返回 null */
  @JvmStatic
  fun llmActive(ctx: Context): JSONObject? {
    val llm = cfg(ctx).optJSONObject("llm") ?: return null
    val id = llm.optString("active").ifBlank { "llm1" }
    val s = llm.optJSONObject("slots")?.optJSONObject(id) ?: return null
    return s.put("id", id)
  }

  @JvmStatic
  fun status(ctx: Context): String {
    val c = cfg(ctx)
    if (!c.optBoolean("enabled")) return ctx.getString(com.selfhost.layatest.R.string.gw_disabled)
    val parts = ArrayList<String>()
    if (c.optJSONObject("email")?.optBoolean("enabled") == true) parts.add(ctx.getString(com.selfhost.layatest.R.string.mail_running))
    if (c.optJSONObject("mqtt")?.optBoolean("enabled") == true) parts.add(ctx.getString(com.selfhost.layatest.R.string.mqtt_connected, c.optJSONObject("mqtt")?.optString("url")))
    if (UploadServer.running()) parts.add(ctx.getString(com.selfhost.layatest.R.string.upload_running, UploadServer.url()))
    if (DecisionApiServer.running()) parts.add(ctx.getString(com.selfhost.layatest.R.string.api_running, DecisionApiServer.url()))
    return if (parts.isEmpty()) ctx.getString(com.selfhost.layatest.R.string.gw_enabled_no_ch) else parts.joinToString(";")
  }

  /** app 启动时恢复(仅 enabled 时拉起) */
  @JvmStatic
  fun autoStart(ctx: Context) {
    if (cfg(ctx).optBoolean("enabled")) start(ctx)
  }

  @Synchronized
  fun start(ctx: Context) {
    UploadServer.start(ctx) // 上传服务随网关常驻(局域网页面/接口)
    val c = cfg(ctx)
    val api = c.optJSONObject("api")
    if (api?.optBoolean("enabled") == true) DecisionApiServer.start(ctx, api.optInt("port", DecisionApiServer.PORT))
    val email = c.optJSONObject("email")
    if (email?.optBoolean("enabled") == true && !emailRunning) startEmail(ctx, email, c.optJSONObject("report"))
    val mq = c.optJSONObject("mqtt")
    if (mq?.optBoolean("enabled") == true && !mqttRunning) startMqtt(ctx, mq, c.optJSONObject("topics"))
  }

  // ---- 邮件网关 ----
  private fun startEmail(ctx: Context, email: JSONObject, report: JSONObject?) {
    emailRunning = true
    Thread {
      var lastReportDay = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("lastReportDay", "") ?: ""
      while (emailRunning) {
        try {
          pollEmail(ctx, email)
        } catch (t: Throwable) {
          Log.w(TAG, "email poll: ${t.message}")
        }
        try {
          val to = report?.optString("to") ?: ""
          val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
          if (to.isNotEmpty() && day != lastReportDay) {
            sendMail(email, to, ctx.getString(com.selfhost.layatest.R.string.mail_daily_subject, day), DecisionCore.report(ctx, 0))
            lastReportDay = day
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("lastReportDay", day).apply()
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
    val host = email.optString("host"); val user = email.optString("user"); val pass = email.optString("pass")
    if (host.isEmpty() || user.isEmpty()) return
    val ssl = email.optBoolean("ssl", true)
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
            ctx.getString(com.selfhost.layatest.R.string.mail_reply_subject, msg.subject ?: ""), DecisionCore.fmt(ctx, task, r.answers))
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
    val host = email.optString("host"); val user = email.optString("user"); val pass = email.optString("pass")
    val useSsl = email.optBoolean("ssl", true)
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
      val host = email.optString("host"); val user = email.optString("user"); val pass = email.optString("pass")
      if (host.isEmpty() || user.isEmpty()) return ctx.getString(com.selfhost.layatest.R.string.imap_fill_host)
      val ssl = email.optBoolean("ssl", true)
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
      val url = mq.optString("url")
      if (url.isEmpty()) return ctx.getString(com.selfhost.layatest.R.string.mqtt_fill_url)
      val sub = topics?.optString("sub")?.ifEmpty { "laya/req/+" } ?: "laya/req/+"
      val pub = topics?.optString("pub")?.ifEmpty { "laya/resp" } ?: "laya/resp"
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
        val sub = topics?.optString("sub")?.ifEmpty { "laya/req/+" } ?: "laya/req/+"
        val pub = topics?.optString("pub")?.ifEmpty { "laya/resp" } ?: "laya/resp"
        val client = MqttClient(mq.optString("url"), "laya-apk-" + UUID.randomUUID().toString().take(8), MemoryPersistence())
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
