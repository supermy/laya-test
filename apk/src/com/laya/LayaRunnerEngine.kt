package com.laya

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 独立进程推理引擎:APK 内置 litert-runner(socket 服务,已验证 17ms/问)。
 * 主进程与 runner 子进程以 unix socket 通信(协议与 litert-runner.c 一致)。
 * 动机:app 主进程内 CL 回读 137ms(bench 同刻 16ms)——进程隔离绕开主进程
 * 的 GPU 提交路径差异;runner 与 Termux bench 同为独立进程域。
 */
class LayaRunnerEngine(private val context: Context, private val baseDir: File) : DecisionEngine {

  private val appContext = context.applicationContext
  private val tokenizer = LayaTokenizer(File(baseDir, "tokenizer.json"))
  private val calibration = LayaCalibration.load(File(baseDir, "laya_ml_calibration.json"))
  private val builder = LayaPromptBuilder(tokenizer, maxLen = WINDOW, headMaxLen = 256)

  private val task: String = baseDir.name.removePrefix("laya-")
  private val sockFile = File(context.filesDir, "laya-$task.sock")
  private val cacheDir = File(context.filesDir, "gpucache-$task").apply { mkdirs() }
  private var proc: Process? = null
  private var sock: android.net.LocalSocket? = null
  private var din: java.io.DataInputStream? = null
  private var dout: java.io.OutputStream? = null

  init {
    spawnRunner()
    connectWithRetry()
    ping()
    Log.i(TAG, "runner ready: $task")
  }

  private fun spawnRunner() {
    sockFile.delete()
    val nl = appContext.applicationInfo.nativeLibraryDir
    val main = File(baseDir, "laya_ml_s256_embeds_wfp16.tflite").absolutePath
    val act = File(baseDir, "laya_ml_act_head_fp32.tflite").absolutePath
    val emb = File(baseDir, "token_embeddings_fp16.bin").absolutePath
    val pb = ProcessBuilder(
      "$nl/librunner_rt.so", main, act, emb, sockFile.absolutePath, cacheDir.absolutePath,
    ).directory(appContext.filesDir).redirectErrorStream(true)
    pb.environment()["LD_LIBRARY_PATH"] = nl
    pb.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
    proc = pb.start().also { p ->
      Thread {
        p.inputStream.bufferedReader().forEachLine { Log.i("RunnerProc", it) }
      }.apply { isDaemon = true; name = "Runner-Log" }.start()
    }
  }

  private fun connectWithRetry() {
    val deadline = SystemClock.elapsedRealtime() + 60_000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (sockFile.exists() && sockFile.length() > 0L) {
        try {
          val s = android.net.LocalSocket()
          s.connect(android.net.LocalSocketAddress(sockFile.absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
          s.soTimeout = 30_000
          sock = s
          din = java.io.DataInputStream(s.inputStream)
          dout = s.outputStream
          return
        } catch (t: Throwable) {
          Log.w(TAG, "connect retry: ${t.message}")
        }
      }
      proc?.let { p ->
        if (!p.isAlive) throw IllegalStateException("runner 提前退出(exit=${p.exitValue()}),常见原因: socket bind 失败/模型文件缺失")
      }
      SystemClock.sleep(200)
    }
    throw IllegalStateException("runner socket timeout")
  }

  private fun send(frame: ByteBuffer) {
    val out = dout ?: throw IllegalStateException("runner not connected")
    frame.flip()
    val bytes = ByteArray(frame.remaining())
    frame.get(bytes)
    out.write(bytes)
    out.flush()
  }

  private fun readFull(n: Int): ByteArray {
    val din = din ?: throw IllegalStateException("runner not connected")
    val out = ByteArray(n)
    var off = 0
    while (off < n) {
      val r = din.read(out, off, n - off)
      if (r < 0) throw IllegalStateException("runner EOF")
      off += r
    }
    return out
  }

  private fun u32(b: ByteArray, off: Int) =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
      ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

  private fun ping() {
    val buf = ByteBuffer.allocate(4 + 20).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(20 + 12) // total = 20 hdr + 12 (magic+status+pad... runner: rsp u32 total; hdr 16? ping resp: total=HDR_RSP=16)
    // 实际 ping: request total=20 (hdr only, cmd=1)
    buf.clear()
    buf.putInt(20)
    buf.putInt(MAGIC); buf.put(1); buf.put(0); buf.put(0); buf.put(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)
    send(buf)
    val resp = readFull(4 + 16)
    val status = resp[4 + 4].toInt() and 0xFF
    check(status == 0) { "runner ping failed" }
  }

  /** 三问之一:返回 markerLogits[K] + act[2](K=option 数) */
  @Synchronized
  fun infer(ids: IntArray, markers: IntArray, qtype: Int): FloatArray {
    val K = markers.size
    val payload = WINDOW * 16 + K * 9 + 8
    val req = ByteBuffer.allocate(4 + 20 + payload).order(ByteOrder.LITTLE_ENDIAN)
    req.putInt(20 + payload)
    req.putInt(MAGIC); req.put(0); req.put(0); req.put(0); req.put(0)
    req.putInt(1); req.putInt(WINDOW); req.putInt(K)
    for (i in 0 until WINDOW) req.putLong(if (i < ids.size) ids[i].toLong() else 0L)
    for (i in 0 until WINDOW) req.putLong(if (i < ids.size) 1L else 0L)
    for (m in markers) req.putLong(m.toLong())
    for (i in 0 until K) req.put(1)
    req.putLong(qtype.toLong())
    send(req)

    val head = readFull(4)
    val total = java.nio.ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
    check(total in 16..(16 + 64 * 4 + 8 * 4)) { "bad rsp total $total" }
    val body = readFull(total - 4)
    val magic = u32(body, 0)
    check(magic == MAGIC) { "bad magic" }
    val status = body[4].toInt() and 0xFF
    check(status == 0) { "runner error: " + String(body, 20, body.size - 20, Charsets.UTF_8) }
    val actDim = body[5].toInt() and 0xFF
    val logitsN = u32(body, 12)
    val out = FloatArray(logitsN + actDim)
    java.nio.ByteBuffer.wrap(body, 16, body.size - 16).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    return out
  }

  /** 与 LayaNativeEngine.answer 同语义:单问决策 */
  override fun answer(state: Any?, question: Map<String, Any?>, questionId: String): Map<String, Any?> {
    val t0 = SystemClock.elapsedRealtime()
    val seq = builder.build(state, question, questionId)
    val t1 = SystemClock.elapsedRealtime()
    val out = infer(seq.ids, seq.markers, seq.question.qtype)
    val t2 = SystemClock.elapsedRealtime()
    Log.i(TAG, "perf qid=$questionId build=${t1 - t0}ms infer=${t2 - t1}ms")
    val k = seq.markers.size
    val markerLogits = out.copyOfRange(0, k)
    val actLogits = out.copyOfRange(k, k + 2)
    return LayaDecoder.decode(markerLogits, actLogits, seq.question, calibration)
  }

  override fun close() {
    try {
      val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
      buf.putInt(20); buf.putInt(MAGIC); buf.put(2); buf.put(0); buf.put(0); buf.put(0)
      buf.putInt(0); buf.putInt(0); buf.putInt(0)
      send(buf)
    } catch (_: Throwable) {}
    try { sock?.close() } catch (_: Throwable) {}
    sock = null
    // 确保子进程死透(残留进程会占住 socket 文件,导致重建 bind 失败)
    proc?.let { p ->
      p.destroy()
      try { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
    }
    proc = null
    sockFile.delete()
  }

  companion object {
    private const val TAG = "RunnerEngine"
    private const val MAGIC = 0x4C415941
    const val WINDOW = 256
  }
}
