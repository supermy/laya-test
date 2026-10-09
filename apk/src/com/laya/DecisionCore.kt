package com.laya

import android.content.Context
import android.util.Log
import com.selfhost.layatest.R
import org.json.JSONArray
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
    "multi" to "多语言工单分流(基础)",
  )
  private val BUILTIN_RES = mapOf(
    "ticket" to com.selfhost.layatest.R.string.biz_ticket, "ugc" to com.selfhost.layatest.R.string.biz_ugc, "agent" to com.selfhost.layatest.R.string.biz_agent,
    "risk" to com.selfhost.layatest.R.string.biz_risk, "multi" to com.selfhost.layatest.R.string.biz_multi,
  )

  /** 已安装业务:/sdcard/models 下 laya-litert-* 目录的 phone 子目录含全套模型文件;内置五业务 + 动态包 */
  @JvmStatic
  @JvmOverloads
  fun scanTasks(ctx: Context? = null, sdRoot: String = "/sdcard/models"): List<Pair<String, String>> {
    val out = LinkedHashMap<String, String>()
    val root = File(sdRoot)
    val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("laya-litert-") } ?: emptyArray()
    for (d in dirs) {
      val task = d.name.removePrefix("laya-litert-")
      val pkg = File(d, "phone")
      val ready = pkg.isDirectory && (File(pkg, "laya_ml_s256_embeds_wfp16.tflite").isFile ||
          File(pkg, "laya_ml_s256_embeds_npu.tflite").isFile)
      if (ready) out[task] = BUILTIN_RES[task]?.let { ctx?.getString(it) } ?: BUILTIN[task]
          ?: File(pkg, "label.txt").takeIf { it.isFile }?.readText()?.trim() ?: task
    }
    // multi 基础模型:目录名为 laya-litert(无后缀),模型即多语言工单分流
    val multiPkg = File(root, "laya-litert/phone")
    if (multiPkg.isDirectory && File(multiPkg, "laya_ml_s256_embeds_npu.tflite").isFile)
      out["multi"] = BUILTIN_RES["multi"]?.let { ctx?.getString(it) } ?: BUILTIN["multi"]!!
    return out.toList()
  }

  private var jniEngine: DecisionEngine? = null
  private var runnerEngine: DecisionEngine? = null
  private var engineTask: String? = null
  private var engineDesc: String? = null

  // ---- 硬件探测:引擎后端按装机手机资源自动确定(NPU → GPU → CPU 兜底) ----

  /** 仅高通 SoC 且 APK 打包了 QNN 库时 NPU 可用;MTK 等直接落 GPU/CPU */
  @JvmStatic
  fun npuAvailable(ctx: Context): Boolean {
    val soc = if (android.os.Build.VERSION.SDK_INT >= 31)
      "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}" else android.os.Build.HARDWARE
    val qualcomm = soc.contains("qcom", true) || soc.contains("qualcomm", true) ||
        android.os.Build.HARDWARE.startsWith("qcom")
    return qualcomm && LayaEngine.npuLibrariesInstalled(ctx)
  }

  /** MTK 天玑:dispatch 库已打包(APK 内 libLiteRtDispatch_MediaTek.so)即具备 MDLA NPU 能力 */
  fun mtkNpuAvailable(ctx: Context): Boolean {
    val soc = if (android.os.Build.VERSION.SDK_INT >= 31)
      android.os.Build.SOC_MANUFACTURER else ""
    if (!soc.contains("MediaTek", true) && !android.os.Build.HARDWARE.contains("mt6", true)) return false
    val nl = ctx.applicationInfo.nativeLibraryDir
    return File(nl, "libLiteRtDispatch_MediaTek.so").isFile &&
        File(nl, "libnpu_rt.so").isFile
  }

  /** 业务模型包是否携带 NPU dispatch 主图 + scorer bin */
  fun npuModelReady(base: File): Boolean =
    File(base, "laya_ml_s256_embeds_npu.tflite").isFile && File(base, "laya_ml_scorer.bin").isFile

  private fun openclPresent(): Boolean =
    listOf("/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so")
      .any { File(it).isFile }

  /** 当前实际引擎描述(决策卡片/系统页展示) */
  @JvmStatic
  fun currentEngine(ctx: Context): String = engineDesc ?: ctx.getString(com.selfhost.layatest.R.string.engine_uninitialized)

  /** 引擎与硬件情况(系统页展示) */
  @JvmStatic
  fun backendInfo(ctx: Context): String {
    val soc = if (android.os.Build.VERSION.SDK_INT >= 31)
      "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}" else android.os.Build.HARDWARE
    val npu = if (npuAvailable(ctx)) ctx.getString(com.selfhost.layatest.R.string.npu_ok_qnn)
      else if (mtkNpuAvailable(ctx)) ctx.getString(com.selfhost.layatest.R.string.npu_ok_mtk)
      else ctx.getString(com.selfhost.layatest.R.string.npu_unavail)
    val gpu = if (openclPresent()) ctx.getString(com.selfhost.layatest.R.string.gpu_opencl_ok) else ctx.getString(com.selfhost.layatest.R.string.gpu_opencl_try)
    return ctx.getString(com.selfhost.layatest.R.string.backend_fmt, soc, npu, gpu, engineDesc ?: ctx.getString(com.selfhost.layatest.R.string.engine_uninitialized))
  }

  /** GPU→CPU 逐级尝试(runner 独立进程最快,JNI 进程内次之,CPU 兜底) */
  private fun gpuOrCpu(ctx: Context, base: File): DecisionEngine =
    try {
      LayaRunnerEngine(ctx, base).also { engineDesc = ctx.getString(com.selfhost.layatest.R.string.engine_gpu_runner) }
    } catch (t: Throwable) {
      Log.w("DecisionCore", "runner failed, fallback JNI GPU/CPU", t)
      try {
        LayaNativeEngine(ctx, base, true).also { engineDesc = ctx.getString(com.selfhost.layatest.R.string.engine_gpu_jni) }
      } catch (t2: Throwable) {
        Log.w("DecisionCore", "jni gpu failed, fallback cpu", t2)
        LayaNativeEngine(ctx, base, false).also { engineDesc = ctx.getString(com.selfhost.layatest.R.string.engine_cpu_fallback) }
      }
    }

  /** 进程内 LayaEngine 适配(NPU 专用路径):answer 接口对齐 DecisionEngine */
  private class LayaFullEngine(context: Context, base: File) : DecisionEngine {
    private val eng = LayaEngine(context, LayaEngine.Storage.WFP16, base)
    init { eng.initialize(LayaEngine.Backend.NPU) }
    override fun answer(state: Any?, question: Map<String, Any?>, questionId: String): Map<String, Any?> =
      eng.answer(state, question, LayaEngine.Backend.NPU, questionId).answer
    override fun close() { eng.close() }
  }

  private fun q(type: String, ins: String, criteria: Any? = null): Map<String, Any?> =
    LinkedHashMap<String, Any?>().apply {
      put("type", type); put("instructions", ins)
      if (criteria != null) put("criteria", criteria)
    }

  private fun cl(vararg kv: String): Map<String, Any?> =
    LinkedHashMap<String, Any?>().apply { kv.forEach { put(it, null) } }

  fun questionDefs(ctx: Context?, task: String): List<Pair<String, Map<String, Any?>>> {
    if (task !in BUILTIN) return externalDefs(task)
    return builtinDefs(ctx, task)
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

  private fun builtinDefs(ctx: Context?, task: String): List<Pair<String, Map<String, Any?>>> = when (task) {
    // criteria/legend 为模型输出类别键,保持存储原值;仅 instructions(问题文本)本地化
    "ticket", "multi" -> listOf(
      "q0" to q("choice", ctx.getString2(com.selfhost.layatest.R.string.q_ticket_0), cl("技术支持", "账单计费", "销售咨询", "退换货", "故障维护", "其他")),
      "q1" to q("score", ctx.getString2(com.selfhost.layatest.R.string.q_ticket_1), listOf("可忽略", "低", "中", "高", "紧急")),
      "q2" to q("noul", ctx.getString2(com.selfhost.layatest.R.string.q_ticket_2)),
    )
    "ugc" -> listOf(
      "q0" to q("choice", ctx.getString2(com.selfhost.layatest.R.string.q_ugc_0), cl("正常", "广告导流", "辱骂攻击", "色情低俗", "诈骗引流")),
      "q1" to q("score", ctx.getString2(com.selfhost.layatest.R.string.q_ugc_1), listOf("无违规", "轻微", "一般", "较重", "严重")),
      "q2" to q("noul", ctx.getString2(com.selfhost.layatest.R.string.q_ugc_2)),
    )
    "agent" -> listOf(
      "q0" to q("choice", ctx.getString2(com.selfhost.layatest.R.string.q_agent_0), cl("直接回答", "检索问答", "工具调用", "多步规划", "转人工")),
      "q1" to q("score", ctx.getString2(com.selfhost.layatest.R.string.q_agent_1), listOf("一句话", "简单", "中等", "复杂", "极复杂")),
      "q2" to q("noul", ctx.getString2(com.selfhost.layatest.R.string.q_agent_2)),
    )
    else -> listOf(
      "q0" to q("score", ctx.getString2(com.selfhost.layatest.R.string.q_risk_0), listOf("无信号", "弱", "中", "强", "极强")),
      "q1" to q("choice", ctx.getString2(com.selfhost.layatest.R.string.q_risk_1), cl("无风险信号", "信用风险", "欺诈风险", "合规风险")),
      "q2" to q("noul", ctx.getString2(com.selfhost.layatest.R.string.q_risk_2)),
    )
  }

  /** ctx 可空的 getString(无 ctx 路径兜底空串) */
  private fun Context?.getString2(id: Int): String = this?.getString(id) ?: ""

  @Synchronized
  private fun ensureRunner(ctx: Context, task: String): DecisionEngine {
    if (runnerEngine != null && engineTask == task) return runnerEngine!!
    jniEngine?.let { try { it.close() } catch (_: Throwable) {} }
    jniEngine = null
    val base = File(ctx.filesDir, "laya-$task").apply { mkdirs() }
    // multi 基础模型目录名为 laya-litert(无后缀)
    val src = if (task == "multi") File("/sdcard/models/laya-litert/phone")
              else File("/sdcard/models/laya-litert-$task/phone")
    val files = listOf(
      "laya_ml_act_head_fp32.tflite",
      "token_embeddings_fp16.bin", "token_embeddings.json",
      "laya_ml_calibration.json", "tokenizer.json",
    )
    for (f in files) {
      val s = File(src, f)
      check(s.isFile) { ctx.getString(com.selfhost.layatest.R.string.model_file_missing, s.absolutePath) }
      val d = File(base, f)
      if (!d.isFile || d.length() != s.length()) s.copyTo(d, overwrite = true)
    }
    // 主图:NPU dispatch 与 GPU wfp16 至少其一(纯 NPU 包可无 wfp16)
    var hasMain = false
    for (f in listOf("laya_ml_s256_embeds_npu.tflite", "laya_ml_s256_embeds_wfp16.tflite")) {
      val s = File(src, f)
      if (s.isFile) {
        hasMain = true
        val d = File(base, f)
        if (!d.isFile || d.length() != s.length()) s.copyTo(d, overwrite = true)
      }
    }
    check(hasMain) { ctx.getString(com.selfhost.layatest.R.string.main_graph_missing, src.absolutePath) }
    // NPU 附加文件(scorer bin)存在才复制(GPU 兜底不依赖)
    val sc = File(src, "laya_ml_scorer.bin")
    if (sc.isFile) {
      val d = File(base, sc.name)
      if (!d.isFile || d.length() != sc.length()) sc.copyTo(d, overwrite = true)
    }
    val e: DecisionEngine =
      if (npuAvailable(ctx)) {
        try {
          Log.i("DecisionCore", "NPU available, trying QNN engine first")
          LayaFullEngine(ctx, base).also { engineDesc = ctx.getString(com.selfhost.layatest.R.string.engine_npu_qnn) }
        } catch (t: Throwable) {
          Log.w("DecisionCore", "npu engine failed, degrade to gpu/cpu", t)
          gpuOrCpu(ctx, base)
        }
      } else if (mtkNpuAvailable(ctx) && npuModelReady(base)) {
        try {
          Log.i("DecisionCore", "MTK NPU available, trying dispatch runner")
          LayaRunnerEngine(ctx, base, npu = true).also { engineDesc = ctx.getString(com.selfhost.layatest.R.string.engine_npu_mtk) }
        } catch (t: Throwable) {
          Log.w("DecisionCore", "mtk npu runner failed, degrade to gpu/cpu", t)
          gpuOrCpu(ctx, base)
        }
      } else gpuOrCpu(ctx, base)
    runnerEngine = e; engineTask = task
    return e
  }

  /**
   * 手动导入模型包:source 为含 6 个模型文件的目录,或 .zip 压缩包。
   * 复制到 /sdcard/models/laya-litert-<task>/phone/,成功返回 null,失败返回错误信息。
   */
  @JvmStatic
  fun importPackage(ctx: Context, source: String, task: String): String? {
    if (!task.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) return ctx.getString(com.selfhost.layatest.R.string.import_name_invalid)
    if (!source.lowercase().endsWith(".zip") && !File(source).isDirectory) return ctx.getString(com.selfhost.layatest.R.string.import_src_missing, source)
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
            if (!f.canonicalPath.startsWith(outRoot)) return ctx.getString(com.selfhost.layatest.R.string.import_zip_path, e.name)
            if (e.isDirectory) f.mkdirs()
            else {
              f.parentFile?.mkdirs()
              java.io.FileOutputStream(f).use { zis.copyTo(it) }
            }
            e = zis.nextEntry
          }
        }
      } catch (t: Throwable) { tmp.deleteRecursively(); return ctx.getString(com.selfhost.layatest.R.string.import_unzip_failed, t.message ?: "") }
    } else tmp = File(source)
    val missing = required.filterNot { File(tmp, it).isFile() }
    if (missing.isNotEmpty()) { if (source.lowercase().endsWith(".zip")) tmp.deleteRecursively(); return ctx.getString(com.selfhost.layatest.R.string.import_missing_files, missing.toString()) }
    // 命名/内容检测:questions.json(若携带)必须是 JSON 数组
    val qf = File(tmp, "questions.json")
    if (qf.isFile) {
      try { org.json.JSONArray(qf.readText()) } catch (t: Throwable) {
        if (source.lowercase().endsWith(".zip")) tmp.deleteRecursively()
        return ctx.getString(com.selfhost.layatest.R.string.import_questions_bad, t.message ?: "")
      }
    }
    val dst = File("/sdcard/models/laya-litert-$task/phone").apply { mkdirs() }
    try {
      for (f in tmp.listFiles() ?: emptyArray()) {
        if (f.isFile) f.copyTo(File(dst, f.name), overwrite = true)
      }
    } catch (t: Throwable) { return ctx.getString(com.selfhost.layatest.R.string.import_copy_failed, t.message ?: "") }
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
    engineDesc = null
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
    for ((qid, qdef) in questionDefs(ctx, task)) {
      answers.put(qid, JSONObject(eng.answer(text, qdef, qid)))
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    appendLog(ctx, task, text, answers, ms)
    // 决策分流:等级"高"(重要+紧急)→ 决策后升级,异步拉 LLM 生成处理建议(独立 llm 日志行,不阻塞本次决策)
    llmFollowUp(ctx, task, text, answers)
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

/** LLM 升级完成回调(UI 展示风险分析) */
fun interface LlmListener { fun onLlmDone(task: String, content: String, error: String?) }

  @JvmStatic @Volatile var llmListener: LlmListener? = null

  /** 最近一条 LLM 升级结果(业务维度,type=llm 行;无则 null) */
  @JvmStatic
  fun lastLlm(ctx: Context, task: String): JSONObject? {
    var out: JSONObject? = null
    try {
      val f = logFile(ctx)
      if (f.isFile) for (line in java.nio.file.Files.readAllBytes(f.toPath()).toString(Charsets.UTF_8).split("\n").asReversed()) {
        if (line.isBlank()) continue
        try {
          val o = JSONObject(line)
          if (o.optString("type") == "llm" && o.optString("task") == task) { out = o; break }
        } catch (_: Exception) {}
      }
    } catch (_: Throwable) {}
    return out
  }

  /**
   * 决策后 LLM 升级通道:等级"高"(重要+紧急)且配置了 LLM 槽位时,
   * 异步调 OpenAI 兼容 /chat/completions 生成处理建议,追加独立日志行 {"type":"llm",...}。
   * 不阻塞本次决策返回;LLM 失败只记 error 字段,不影响决策主流程。
   */
  private fun llmFollowUp(ctx: Context, task: String, text: String, answers: JSONObject) {
    try {
      if (levelOf(JSONObject().put("decoded", answers)).optString("level") != "高") return
      val slot = Gateway.llmActive(ctx) ?: return
      val base = slot.optString("baseURL").trim().trimEnd('/')
      val model = slot.optString("model").trim()
      if (base.isEmpty() || model.isEmpty()) return
      Thread {
        val out = JSONObject()
        var err: String? = null
        val t0 = System.currentTimeMillis()
        try {
          val prompt = "业务「$task」决策模型输出:\n${fmt(ctx, task, answers)}\n\n原始输入:${text.take(400)}\n\n" +
              "该单已被分流为重要+紧急,升级到 LLM 做决策后处理。请给出:1) 风险/影响判断 2) 建议处理动作 3) 是否需人工介入。中文,200 字内。"
          val c = (java.net.URL(base + "/chat/completions").openConnection() as java.net.HttpURLConnection)
          c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 240_000
          c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
          val key = slot.optString("apiKey")
          if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
          c.outputStream.use { os ->
            os.write(JSONObject().put("model", model).put("max_tokens", 700)
              .put("chat_template_kwargs", JSONObject().put("enable_thinking", false)) // Qwen3 系思考模型免思维链,不支持的服务端忽略此字段
              .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
              .toString().toByteArray())
          }
          val code = c.responseCode
          val body = (if (code < 400) c.inputStream else c.errorStream).readBytes().toString(Charsets.UTF_8)
          check(code < 400) { "HTTP $code: ${body.take(150)}" }
          val msg = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
          var content = msg?.optString("content")?.trim() ?: ""
          if (content.isEmpty()) content = msg?.optString("reasoning_content")?.trim() ?: "" // 思考型模型正文兜底
          out.put("model", model).put("content", content)
        } catch (t: Throwable) {
          err = (t.message ?: t.toString()).take(200)
        }
        try {
          val o = JSONObject().put("ts", System.currentTimeMillis()).put("type", "llm").put("task", task)
            .put("latencyMs", System.currentTimeMillis() - t0)
          if (err != null) o.put("error", err) else o.put("llm", out)
          java.io.FileOutputStream(logFile(ctx), true).use { it.write((o.toString() + "\n").toByteArray()) }
        } catch (_: Exception) {}
        try { llmListener?.onLlmDone(task, out.optString("content"), err) } catch (_: Throwable) {}
      }.apply { isDaemon = true; name = "LlmFollowUp"; start() }
    } catch (_: Throwable) {}
  }

  /** 某业务的历史决策(当前月日志,按时间正序,最多 limit 条;type=llm 行跳过) */
  @JvmStatic
  fun history(ctx: Context, task: String, limit: Int = 30): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    val f = logFile(ctx)
    if (!f.isFile) return out
    for (line in java.nio.file.Files.readAllBytes(f.toPath()).toString(Charsets.UTF_8).split("\n")) {
      if (line.isBlank()) continue
      try { val o = JSONObject(line); if (o.optString("type") == "llm") continue; if (o.optString("task") == task) out.add(o) } catch (_: Exception) {}
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
    val all2 = all.filter { it.optString("type") != "llm" }
    val pages = Math.max(1, Math.ceil(all2.size / pageSize.toDouble()).toInt())
    val p = page.coerceIn(0, pages - 1)
    val slice = all2.drop(p * pageSize).take(pageSize)
    val df = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    val rows = JSONArray()
    // llm 升级行按业务分组,匹配决策行之后 10 分钟内的首条(type=llm)
    val llmByTask = all.filter { it.optString("type") == "llm" }.groupBy { it.optString("task") }
    for (e in slice) {
      val st = e.optString("state")
      val parts = ArrayList<String>()
      var result = ""
      var score = ""
      var verdict = ""
      val dec = e.optJSONObject("decoded")
      if (dec != null) {
        val kit = dec.keys()
        while (kit.hasNext()) {
          val a = dec.optJSONObject(kit.next()) ?: continue
          when (a.optString("type")) {
            "choice" -> { parts.add(a.optString("choice")); if (result.isEmpty()) result = a.optString("choice") }
            "score" -> { val s = ctx.getString(com.selfhost.layatest.R.string.score_fmt, a.optDouble("score")); parts.add(s); if (score.isEmpty()) score = s }
            "noul" -> { val v = if (a.optDouble("noul") >= 0.5) ctx.getString(com.selfhost.layatest.R.string.verdict_human) else ctx.getString(com.selfhost.layatest.R.string.verdict_auto); parts.add(v); if (verdict.isEmpty()) verdict = v }
          }
        }
      }
      var llmTxt = ""
      val cands = llmByTask[e.optString("task")]
      if (cands != null) for (l in cands) {
        if (l.optLong("ts") >= e.optLong("ts") && l.optLong("ts") - e.optLong("ts") < 600_000L) {
          val lo = l.optJSONObject("llm")
          llmTxt = if (lo != null) lo.optString("content") else ctx.getString(com.selfhost.layatest.R.string.llm_failed_prefix, l.optString("error"))
          break
        }
      }
      val content = st.let { if (it.length > 60) it.substring(0, 60) + "…" else it } +
        if (llmTxt.isEmpty()) "" else "\n🤖 " + (if (llmTxt.length > 100) llmTxt.substring(0, 100) + "…" else llmTxt)
      rows.put(JSONObject()
        .put("time", df.format(Date(e.optLong("ts"))))
        .put("task", e.optString("task"))
        .put("result", if (result.isEmpty()) "-" else result)
        .put("score", if (score.isEmpty()) "-" else score)
        .put("verdict", if (verdict.isEmpty()) "-" else verdict)
        .put("level", levelOf(e).optString("level"))
        .put("latencyMs", e.optLong("latencyMs"))
        .put("summary", parts.joinToString(" | "))
        .put("state", content))
    }
    val o = JSONObject()
    o.put("rows", rows)
    o.put("page", p + 1); o.put("pages", pages)
    return o
  }

  /** 报表表格数据:kind 0日报 1月报 2年报 → {total, rows:[{period,count,avgLatency,top,dist}]} */
  @JvmStatic
  fun reportTable(ctx: Context, kind: Int): JSONObject {
    val f = logFile(ctx)
    val all = ArrayList<JSONObject>()
    if (f.isFile) {
      for (line in java.nio.file.Files.readAllBytes(f.toPath()).toString(Charsets.UTF_8).split("\n")) {
        if (line.isBlank()) continue
        try { all.add(JSONObject(line)) } catch (_: Exception) {}
      }
    }
    val df = when (kind) { 2 -> SimpleDateFormat("yyyy", Locale.US); 1 -> SimpleDateFormat("yyyy-MM", Locale.US); else -> SimpleDateFormat("yyyy-MM-dd", Locale.US) }
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
    val rows = JSONArray()
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
      val dist = labels.entries.joinToString(" · ") { "${it.key} ${it.value}" }
      rows.put(JSONObject().put("period", key).put("count", list.size)
        .put("avgLatency", if (list.isEmpty()) 0 else lat / list.size)
        .put("top", labels.maxByOrNull { it.value }?.key ?: "-")
        .put("dist", if (dist.isEmpty()) "-" else dist))
    }
    return JSONObject().put("total", all.size).put("rows", rows)
  }

  /** 决策结果 → 可读文本(邮件回复/MQTT 消息共用) */
  @JvmStatic
  fun fmt(ctx: Context?, task: String, answers: JSONObject): String {
    val label = BUILTIN_RES[task]?.let { ctx?.getString(it) }
      ?: scanTasks(ctx).firstOrNull { it.first == task }?.second ?: task
    val sb = StringBuilder("【").append(label).append("】\n")
    for ((qid, def) in questionDefs(ctx, task)) {
      val a = answers.optJSONObject(qid) ?: continue
      when (a.optString("type")) {
        "choice" -> sb.append("• ").append(def["instructions"]).append(": ").append(a.optString("choice"))
          .append(" (").append(a.optDouble("confidence")).append(")\n")
        "score" -> sb.append("• ").append(def["instructions"]).append(": ").append(a.optDouble("score"))
          .append("/5\n")
        "noul" -> sb.append("• ").append(def["instructions"]).append(": ")
          .append(if (a.optDouble("noul") >= 0.5) (ctx?.getString(com.selfhost.layatest.R.string.yes) ?: "是") else (ctx?.getString(com.selfhost.layatest.R.string.no) ?: "否")).append("\n")
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
    val names = arrayOf(ctx.getString(com.selfhost.layatest.R.string.kind_daily), ctx.getString(com.selfhost.layatest.R.string.kind_monthly), ctx.getString(com.selfhost.layatest.R.string.kind_yearly), ctx.getString(com.selfhost.layatest.R.string.kind_detail))
    val df = when (kind) { 2 -> SimpleDateFormat("yyyy", Locale.US); 1 -> SimpleDateFormat("yyyy-MM", Locale.US); else -> SimpleDateFormat("yyyy-MM-dd", Locale.US) }
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
    val sb = StringBuilder(ctx.getString(com.selfhost.layatest.R.string.report_header_fmt, names[kind], all.size)).append("\n\n")
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
      sb.append(ctx.getString(com.selfhost.layatest.R.string.report_group_fmt, key, list.size, if (list.isEmpty()) 0 else lat / list.size)).append("\n")
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

  // ---- 下钻详单:业务 × 决策等级 × 日期 ----
  // 等级取法:score 问归一化分 ≥0.66 高 / ≥0.33 中 / 否则低;noul 命中=高/无信号=低;纯 choice 用判定标签。
  @JvmStatic
  fun levelOf(e: JSONObject): JSONObject {
    val dec = e.optJSONObject("decoded") ?: e.optJSONObject("answers")
    if (dec != null) {
      val it = dec.keys()
      while (it.hasNext()) {
        val a = dec.optJSONObject(it.next()) ?: continue
        if (a.optString("type") == "score") {
          val legend = a.optJSONObject("legend")
          val maxIdx = if (legend != null && legend.length() > 1) legend.length() - 1 else 4
          val score = a.optDouble("score")
          val n = if (maxIdx > 0) score / maxIdx else 0.0
          val lv = if (n >= 0.66) "高" else if (n >= 0.33) "中" else "低"
          val lab = if (legend != null) legend.optString(Math.round(score).toString()) else ""
          val basis = "score=" + String.format(Locale.US, "%.2f", score) + if (lab.isNullOrBlank()) "" else "($lab)"
          return JSONObject().put("level", lv).put("basis", basis)
        }
      }
      val it2 = dec.keys()
      while (it2.hasNext()) {
        val a = dec.optJSONObject(it2.next()) ?: continue
        if (a.optString("type") == "noul") {
          val p = a.optDouble("noul")
          return JSONObject().put("level", if (p >= 0.5) "低" else "高")
            .put("basis", String.format(Locale.US, "noul=%.2f", p) + if (p >= 0.5) "(无信号)" else "(命中)")
        }
      }
      val it3 = dec.keys()
      while (it3.hasNext()) {
        val a = dec.optJSONObject(it3.next()) ?: continue
        if (a.optString("type") == "choice") {
          val c = a.optString("choice")
          return JSONObject().put("level", c).put("basis", "choice=$c")
        }
      }
    }
    return JSONObject().put("level", "未知").put("basis", "")
  }

  private fun readLogEntries(ctx: Context): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    val f = logFile(ctx)
    if (!f.isFile) return out
    for (line in java.nio.file.Files.readAllBytes(f.toPath()).toString(Charsets.UTF_8).split("\n")) {
      if (line.isBlank()) continue
      try { out.add(JSONObject(line)) } catch (_: Exception) {}
    }
    return out.filter { it.optString("type") != "llm" }
  }

  /** 聚合行:date × task × level → {count, avgLatency};rangeDays ≤0 表示全部 */
  @JvmStatic
  fun detailPivot(ctx: Context, rangeDays: Int, task: String?, level: String?): JSONObject {
    val start = if (rangeDays > 0) System.currentTimeMillis() - rangeDays * 86_400_000L else 0L
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val rows = LinkedHashMap<String, LongArray>()
    var total = 0
    for (e in readLogEntries(ctx)) {
      if (e.optLong("ts") < start) continue
      if (task != null && e.optString("task") != task) continue
      val lv = levelOf(e).optString("level")
      if (level != null && lv != level) continue
      val key = df.format(Date(e.optLong("ts"))) + "|" + e.optString("task") + "|" + lv
      val arr = rows[key] ?: LongArray(2)
      arr[0]++; arr[1] += e.optLong("latencyMs"); rows[key] = arr
      total++
    }
    val outRows = JSONArray()
    for ((key, arr) in rows) {
      val p = key.split("|")
      outRows.put(JSONObject().put("date", p[0]).put("task", p[1]).put("level", p[2])
        .put("count", arr[0]).put("avgLatency", if (arr[0] > 0) arr[1] / arr[0] else 0L))
    }
    return JSONObject().put("total", total).put("rows", outRows)
  }

  /** 下钻明细(最新优先,limit 条):time/task/level/basis/state/answers/latencyMs */
  @JvmStatic
  fun drillList(ctx: Context, date: String?, task: String?, level: String?, limit: Int): JSONArray {
    val out = JSONArray()
    val tf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    var n = 0
    for (e in readLogEntries(ctx).asReversed()) {
      val lv = levelOf(e)
      val lvs = lv.optString("level")
      if (level != null && lvs != level) continue
      if (task != null && e.optString("task") != task) continue
      if (date != null && SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(e.optLong("ts"))) != date) continue
      val o = JSONObject()
      o.put("time", tf.format(Date(e.optLong("ts")))).put("task", e.optString("task"))
        .put("level", lvs).put("basis", lv.optString("basis"))
        .put("state", e.optString("state")).put("latencyMs", e.optLong("latencyMs"))
      val dec = e.optJSONObject("decoded")
      if (dec != null) {
        val parts = ArrayList<String>()
        val it = dec.keys()
        while (it.hasNext()) {
          val a = dec.optJSONObject(it.next()) ?: continue
          when (a.optString("type")) {
            "choice" -> parts.add(a.optString("choice"))
            "score" -> parts.add(ctx.getString(com.selfhost.layatest.R.string.score_fmt, a.optDouble("score")))
            "noul" -> parts.add(if (a.optDouble("noul") >= 0.5) ctx.getString(com.selfhost.layatest.R.string.verdict_human) else ctx.getString(com.selfhost.layatest.R.string.verdict_auto))
          }
        }
        o.put("answers", parts.joinToString(" | "))
      }
      out.put(o)
      if (++n >= limit) break
    }
    return out
  }
}
