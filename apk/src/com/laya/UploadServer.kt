package com.laya

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** 模型包上传服务(局域网):GET / 页面表单;POST /upload?task=<名> 请求体=zip 字节 */
class UploadServer private constructor(private val ctx: Context) : NanoHTTPD("0.0.0.0", PORT) {

  override fun serve(session: IHTTPSession): Response = try {
    when {
      session.method == Method.GET && session.uri == "/" ->
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())

      session.method == Method.POST && session.uri == "/upload" -> {
        val task = session.parameters["task"]?.firstOrNull()?.trim() ?: ""
        val cl = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (task.isEmpty() || cl <= 0)
          json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"需要 ?task=业务名 与 zip 请求体"}""")
        else if (!task.matches(Regex("[a-zA-Z0-9_-]{1,32}")))
          json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"业务名只允许字母/数字/下划线/中划线(1-32 位)"}""")
        else {
          val tmp = File(ctx.cacheDir, "upload-${System.currentTimeMillis()}.zip")
          tmp.outputStream().use { out ->
            val ins = session.inputStream
            val buf = ByteArray(64 * 1024)
            var left = cl
            while (left > 0) {
              val n = ins.read(buf, 0, minOf(buf.size.toLong(), left.toLong()).toInt())
              if (n < 0) break
              out.write(buf, 0, n); left -= n
            }
          }
          val existed = File("/sdcard/models/laya-litert-$task/phone").isDirectory
          val err = DecisionCore.importPackage(ctx, tmp.absolutePath, task)
          tmp.delete()
          if (err == null)
            json(Response.Status.OK, """{"ok":true,"task":"$task","overwrite":$existed,"note":"${if (existed) "已覆盖同名业务" else "新业务已注册"}"}""")
          else
            json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"${err.replace("\"", "'")}"}""")
        }
      }
      else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
    }
  } catch (t: Throwable) {
    Log.w("UploadServer", "serve: ${t.message}")
    json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"${(t.message ?: t.toString()).replace("\"", "'")}"}""")
  }

  private fun json(st: Response.Status, s: String) = newFixedLengthResponse(st, "application/json", s)

  private fun page(): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Laya 模型包上传</title></head>
<body style="font-family:sans-serif;max-width:480px;margin:24px auto;color:#222">
<h3>Laya 模型包上传</h3>
<p>zip 内需含 6 个模型文件(laya_ml_s256_embeds_wfp16.tflite 等),可附 questions.json / label.txt</p>
<p><input id="f" type="file" accept=".zip" style="width:100%"></p>
<p>业务名(英文): <input id="t" style="width:200px"></p>
<p><button style="padding:8px 20px" onclick="up()">上传并注册</button></p>
<pre id="o" style="background:#f4f5f7;padding:10px;white-space:pre-wrap"></pre>
<script>
async function up(){
  const f=document.getElementById('f').files[0];
  const t=document.getElementById('t').value.trim();
  const o=document.getElementById('o');
  if(!f||!t){o.textContent='请选择 zip 并填写业务名';return}
  o.textContent='上传中… '+Math.round(f.size/1048576)+'MB,请稍候';
  try{
    const r=await fetch('/upload?task='+encodeURIComponent(t),{method:'POST',body:f});
    o.textContent=JSON.stringify(await r.json(),null,1);
  }catch(e){o.textContent='上传失败: '+e}
}
</script></body></html>"""

  companion object {
    const val PORT = 8765
    @Volatile private var inst: UploadServer? = null

    @Synchronized
    fun start(ctx: Context) {
      if (inst == null) {
        inst = UploadServer(ctx)
        inst!!.start(SOCKET_READ_TIMEOUT, true)
        Log.i("UploadServer", "up at ${url()}")
      }
    }

    @Synchronized
    fun stopServer() {
      inst?.stop(); inst = null
    }

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
