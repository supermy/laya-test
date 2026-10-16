/** 决策行→旧 JSONL entry 形状映射单测(JVM;纯逻辑,SQLite 行为留给真机 smoke) */
import org.json.JSONObject

private fun chk(name: String, ok: Boolean) {
  println((if (ok) "PASS " else "FAIL ") + name)
  if (!ok) throw AssertionError(name)
}

fun main() {
  // answers JSON(templates 表 answers 列原样)→ 旧 entry {ts,task,state,decoded,latencyMs}
  val answers = """{"q0":{"type":"choice","choice":"技术支持","confidence":0.5},"q1":{"type":"score","score":2.0},"q2":{"type":"noul","noul":0.72}}"""
  val e = JSONObject()
    .put("ts", 1792106284856L)
    .put("task", "ticket")
    .put("state", "打印机连续卡纸")
    .put("decoded", JSONObject(answers))
    .put("latencyMs", 354L)

  // 下游解析契约:decoded 可取回且键齐全(DecisionCore detail/reportTable/drillList 依赖)
  val dec = e.optJSONObject("decoded")!!
  chk("decoded 键回填", dec.length() == 3)
  chk("choice 段可解析", "choice" == dec.optJSONObject("q0")?.optString("type"))
  chk("score 段可解析", dec.optJSONObject("q1")?.optDouble("score") == 2.0)
  chk("noul 段可解析", dec.optJSONObject("q2")?.optDouble("noul") == 0.72)
  chk("state/latencyMs 保留", e.optString("state") == "打印机连续卡纸" && e.optLong("latencyMs") == 354L)

  // llm 行形状:{ts,type,task,latencyMs,llm} 或 {error}
  val ok = JSONObject().put("ts", 1L).put("type", "llm").put("task", "ticket").put("latencyMs", 100L)
    .put("llm", JSONObject().put("model", "m").put("content", "分析"))
  val bad = JSONObject().put("ts", 2L).put("type", "llm").put("task", "ticket").put("latencyMs", 100L)
    .put("error", "HTTP 500")
  chk("llm 行 content 形状", ok.optJSONObject("llm")?.optString("content") == "分析" && !ok.has("error"))
  chk("llm 行 error 形状", bad.optString("error") == "HTTP 500" && !bad.has("llm"))

  println("RESULT: ROWMAP ALL PASS")
}
