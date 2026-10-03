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
    ctx.stopService(Intent(ctx, GatewayService::class.java))
    return "网关已停止(含模型上传服务)"
  }

  @JvmStatic
  fun status(ctx: Context): String {
    val c = cfg(ctx)
    if (!c.optBoolean("enabled")) return "网关未启用"
    val parts = ArrayList<String>()
    if (c.optJSONObject("email")?.optBoolean("enabled") == true) parts.add("邮件网关运行中(60s 轮询)")
    if (c.optJSONObject("mqtt")?.optBoolean("enabled") == true) parts.add("MQTT 已连接 " + c.optJSONObject("mqtt")?.optString("url"))
    if (UploadServer.running()) parts.add("模型上传服务 " + UploadServer.url())
    return if (parts.isEmpty()) "已启用(无通道)" else parts.joinToString(";")
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
            sendMail(email, to, "Laya 日报 $day", DecisionCore.report(ctx, 0))
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
            "Re: ${msg.subject ?: ""} — Laya 决策结果", DecisionCore.fmt(task, r.answers))
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
