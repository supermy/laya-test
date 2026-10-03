package com.laya

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/** JNI 桥:APK 内置 LiteRT C API 推理(与 litert-bench 同款已验证路径)。 */
object LayaJni {
  init { System.loadLibrary("layajni") }

  external fun nativeOpen(main: String, act: String, embed: String, cacheDir: String, useGpu: Boolean): Long
  external fun nativeRun(h: Long, ids: IntArray, markers: IntArray, qtype: Int, window: Int): FloatArray?
  external fun nativeClose(h: Long)
}

/**
 * 单业务内置推理引擎:官方 Kotlin 件(tokenizer/prompt builder/decoder/calibration)
 * + JNI C API 推理。所有 LiteRT 调用固定在单线程(GL 上下文一致性)。
 */
class LayaNativeEngine(private val context: Context, private val baseDir: File, useGpu: Boolean) : DecisionEngine {
  private val tokenizer = LayaTokenizer(File(baseDir, "tokenizer.json"))
  private val calibration = LayaCalibration.load(File(baseDir, "laya_ml_calibration.json"))
  private val builder = LayaPromptBuilder(tokenizer, maxLen = WINDOW, headMaxLen = 256)

  private val executor =
    Executors.newSingleThreadExecutor { r -> Thread(r, "Laya-LiteRT-JNI").apply { isDaemon = true } }

  // 所有 native 调用(含 open)固定在同一线程:GPU 的 GL 上下文是线程绑定的,
  // 跨线程调用会报 InvalidArgument(与 bench 单线程行为保持一致)。
  private var handle: Long = executor.submit(
    java.util.concurrent.Callable {
      LayaJni.nativeOpen(
        File(baseDir, "laya_ml_s256_embeds_wfp16.tflite").absolutePath,
        File(baseDir, "laya_ml_act_head_fp32.tflite").absolutePath,
        File(baseDir, "token_embeddings_fp16.bin").absolutePath,
        File(context.cacheDir, "gpucache").apply { mkdirs() }.absolutePath,
        useGpu,
      )
    },
  ).get()

  init {
    check(handle != 0L) { "LiteRT native open failed (see LayaJni logcat)" }
  }

  /** 单问决策(与 LayaEngine.answer 同语义) */
  override fun answer(
    state: Any?,
    question: Map<String, Any?>,
    questionId: String,
  ): Map<String, Any?> =
    executor.submit(
      java.util.concurrent.Callable {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val seq = builder.build(state, question, questionId)
        val t1 = android.os.SystemClock.elapsedRealtime()
        val out = LayaJni.nativeRun(handle, seq.ids, seq.markers, seq.question.qtype, WINDOW)
          ?: error("nativeRun failed (see LayaJni logcat)")
        val t2 = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("LayaPerf", "qid=$questionId build=${t1 - t0}ms nativeRun=${t2 - t1}ms total=${t2 - t0}ms")
        val k = seq.markers.size
        check(out.size >= k + 2) { "native output too short: ${out.size} < $k+2" }
        val markerLogits = out.copyOfRange(0, k)
        val actLogits = out.copyOfRange(k, k + 2)
        LayaDecoder.decode(markerLogits, actLogits, seq.question, calibration)
      },
    ).get()

  override fun close() {
    val h = handle
    executor.submit { if (h != 0L) LayaJni.nativeClose(h) }.get()
    executor.shutdown()
  }

  companion object {
    const val WINDOW = 256

    /** app 私有模型目录:/sdcard 的 phone 包首次使用拷入 */
    fun modelDir(context: Context, task: String): File {
      val dst = File(context.filesDir, "laya-$task")
      val marker = File(dst, "token_embeddings_fp16.bin")
      if (!marker.isFile) {
        val src = File("/sdcard/models/laya-litert-$task/phone")
        val names = listOf(
          "laya_ml_s256_embeds_wfp16.tflite", "laya_ml_act_head_fp32.tflite",
          "token_embeddings_fp16.bin", "token_embeddings.json",
          "laya_ml_calibration.json", "tokenizer.json",
        )
        dst.mkdirs()
        for (n in names) {
          val s = File(src, n)
          check(s.isFile) { "缺少 $s(先在系统页安装模型)" }
          s.copyTo(File(dst, n), overwrite = true)
        }
        Log.i("LayaNativeEngine", "copied $task model: " + dst.listFiles()?.size + " files")
      }
      return dst
    }
  }
}
