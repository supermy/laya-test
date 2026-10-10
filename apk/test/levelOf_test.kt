import com.laya.DecisionCore
import org.json.JSONObject
import kotlin.system.exitProcess

/** levelOf 纯逻辑单测:等级边界 / noul 翻转 / choice 直通 / basis 格式。
 *  在 JVM 直跑(无 Android 依赖),由 run_tests.sh 编译执行。 */

private fun entry(vararg q: Pair<String, JSONObject>): JSONObject {
  val decoded = JSONObject()
  for ((k, v) in q) decoded.put(k, v)
  return JSONObject().put("decoded", decoded)
}

private fun scoreQ(score: Double): JSONObject =
  JSONObject().put("type", "score").put("score", score)
    .put("legend", JSONObject()
      .put("0", "可忽略").put("1", "低").put("2", "中").put("3", "高").put("4", "紧急"))

private fun noulQ(p: Double): JSONObject = JSONObject().put("type", "noul").put("noul", p)
private fun choiceQ(c: String): JSONObject = JSONObject().put("type", "choice").put("choice", c)

fun main() {
  var fail = 0
  fun chk(name: String, cond: Boolean) {
    println((if (cond) "PASS " else "FAIL ") + name)
    if (!cond) fail++
  }
  val L = { e: JSONObject -> DecisionCore.levelOf(e).optString("level") }

  // score 归一化:score/4 → ≥0.66 高 / ≥0.33 中 / 否则低
  chk("score 3.00 → n=0.75 → 高", L(entry("q1" to scoreQ(3.0))) == "高")
  chk("score 2.00 → n=0.50 → 中", L(entry("q1" to scoreQ(2.0))) == "中")
  chk("score 1.00 → n=0.25 → 低", L(entry("q1" to scoreQ(1.0))) == "低")
  chk("score 2.64 → n=0.66 边界 → 高", L(entry("q1" to scoreQ(2.64))) == "高")
  chk("score 2.63 → n=0.6575 → 中", L(entry("q1" to scoreQ(2.63))) == "中")
  chk("score 1.32 → n=0.33 边界 → 中", L(entry("q1" to scoreQ(1.32))) == "中")
  chk("score 1.31 → n=0.3275 → 低", L(entry("q1" to scoreQ(1.31))) == "低")

  // noul 翻转:≥0.5 无信号=低,<0.5 命中=高(v1.3.0 前曾与 drillEntries 口径相反)
  chk("noul 0.90 无信号 → 低", L(entry("q2" to noulQ(0.9))) == "低")
  chk("noul 0.50 边界 → 低", L(entry("q2" to noulQ(0.5))) == "低")
  chk("noul 0.20 命中 → 高", L(entry("q2" to noulQ(0.2))) == "高")

  // choice 直通:level = 选项值
  chk("choice 直通(账单计费)", L(entry("q0" to choiceQ("账单计费"))) == "账单计费")

  // 空输入 → 未知
  chk("空 decoded → 未知", L(JSONObject()) == "未知")

  // basis 格式:score=%.2f(legend 标签)
  val b = DecisionCore.levelOf(entry("q1" to scoreQ(3.0))).optString("basis")
  chk("basis = score=3.00(高)", b.startsWith("score=3.00") && b.contains("(高)"))

  // 多问优先级:score 问优先于 noul/choice
  chk("score 优先于 noul", L(entry("q1" to scoreQ(3.0), "q2" to noulQ(0.9))) == "高")

  if (fail > 0) { println("RESULT: $fail FAILED"); exitProcess(1) }
  println("RESULT: ALL PASS")
}
