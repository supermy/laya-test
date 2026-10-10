package com.laya

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import org.json.JSONObject

/** 决策 API 服务(NanoHTTPD):
 *  POST /decide  body {"task":"ticket","text":"..."} → 决策 JSON
 *  GET  /health  → {status, tasks, engine, uptimeMs}
 *  随网关启停(Gateway.start 读 cfg "api":{"enabled":true});默认端口 8790 */
class DecisionApiServer private constructor(private val ctx: Context, port: Int) : NanoHTTPD("0.0.0.0", port) {
  private val startedAt = System.currentTimeMillis()

  private fun json(status: Response.Status, o: JSONObject): Response =
    newFixedLengthResponse(status, "application/json", o.toString()).apply {
      addHeader("Access-Control-Allow-Origin", "*")
    }

  private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

  private fun health(): JSONObject {
    val tasks = JSONObject()
    for ((t, l) in DecisionCore.scanTasks(ctx)) tasks.put(t, l)
    return JSONObject().put("ok", true).put("status", "up")
      .put("uptimeMs", System.currentTimeMillis() - startedAt)
      .put("engine", DecisionCore.currentEngine(ctx))
      .put("tasks", tasks)
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri ?: ""
    return try {
      when {
        uri == "/health" -> json(Response.Status.OK, health())
        uri == "/decide" && session.method == Method.POST -> {
          val files = HashMap<String, String>()
          session.parseBody(files)
          val body = JSONObject(files["postData"] ?: "{}")
          val task = body.optString("task", "ticket")
          val text = body.optString("text", "")
          if (text.isEmpty()) json(Response.Status.BAD_REQUEST, err("text is empty"))
          else if (DecisionCore.scanTasks().none { it.first == task })
            json(Response.Status.BAD_REQUEST, err("unknown task: $task"))
          else {
            val r = DecisionCore.decide(ctx, task, text)
            json(Response.Status.OK, JSONObject()
              .put("ok", true)
              .put("task", task)
              .put("engine", DecisionCore.currentEngine(ctx))
              .put("latencyMs", r.latencyMs)
              .put("answers", r.answers)
              .put("result", DecisionCore.fmt(ctx, task, r.answers)))
          }
        }
        else -> json(Response.Status.NOT_FOUND, err("use POST /decide or GET /health"))
      }
    } catch (t: Throwable) {
      Log.w(TAG, "api error: ${t.message}")
      json(Response.Status.INTERNAL_ERROR, err(t.message ?: t.toString()))
    }
  }

  companion object {
    private const val TAG = "DecisionApiServer"
    private const val SOCKET_READ_TIMEOUT = 30_000
    const val PORT = 8790
    @Volatile private var inst: DecisionApiServer? = null

    @JvmStatic
    @Synchronized
    fun start(ctx: Context, port: Int = PORT) {
      stopServer()
      inst = DecisionApiServer(ctx.applicationContext, port).also { it.start(SOCKET_READ_TIMEOUT, true) }
      Log.i(TAG, "up at ${url()}")
    }

    @JvmStatic
    @Synchronized
    fun stopServer() {
      inst?.stop(); inst = null
    }

    @JvmStatic
    fun running(): Boolean = inst != null

    @JvmStatic
    fun url(): String {
      val ip = NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { nif -> nif.inetAddresses.asSequence() }
        .firstOrNull { a -> a is Inet4Address && !a.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"
      return "http://$ip:$PORT"
    }
  }
}
