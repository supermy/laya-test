// ReplyTemplates 100% 覆盖测试:6部门 × 5紧急度 × 2升级 × 2模式(自动/人工) × 2语言 = 120 组合逐项断言
// 加上未知部门回退与非法紧急度钳制。JVM 直跑: java Reply_testKt
import com.laya.ReplyTemplates

fun main() {
  var pass = 0; var fail = 0
  fun ck(cond: Boolean, msg: String) {
    if (cond) pass++ else { fail++; println("FAIL: $msg") }
  }
  // tone 关键词(zh/en 各级唯一,含 SLA):1=一周 2=72小时 3=24小时 4=4小时 5=1小时
  val toneZh = mapOf(1 to "一周", 2 to "72 小时", 3 to "24 小时", 4 to "4 小时", 5 to "1 小时")
  val toneEn = mapOf(1 to "within a week", 2 to "within 72 hours", 3 to "within 24 hours", 4 to "within 4 hours", 5 to "within 1 hour")

  for (locale in listOf("zh", "en")) {
    val en = locale == "en"
    val escKw = if (en) "human agent" else "转接人工"
    val autoSig = if (en) "(This message was sent automatically" else "智能客服助手自动发送"
    val prioKw = if (en) "Customer priority:" else "客户优先级:"
    for (dept in ReplyTemplates.DEPTS) {
      for (u in 1..5) {
        for (esc in listOf(true, false)) {
          val t = ReplyTemplates.autoReply(locale, dept, u, esc)
          ck(t.isNotBlank(), "auto blank $locale/$dept/$u/esc=$esc")
          ck(t.contains(if (en) "Hello" else "您好"), "auto no greeting $locale/$dept/$u/$esc")
          ck(t.contains(autoSig), "auto no signature $locale/$dept/$u/$esc")
          if (esc) {
            ck(t.contains(escKw), "escalate missing kw $locale/$dept/$u")
            ck(!t.contains(if (en) "priority with" else "优先级处理"), "escalate should not promise SLA $locale/$dept/$u")
          } else {
            ck(t.contains(if (en) toneEn[u]!! else toneZh[u]!!), "tone kw wrong $locale/$dept/u=$u")
          }
          ck(t.contains(ReplyTemplates.openSeg(locale, dept)), "dept seg missing $locale/$dept")
          val h = ReplyTemplates.humanScript(locale, dept, u, "CASE-TEXT-$u")
          ck(h.isNotBlank(), "human blank $locale/$dept/$u")
          ck(h.contains("CASE-TEXT-$u"), "human missing user text $locale/$dept/$u")
          ck(h.contains(prioKw) && h.contains("$u/5"), "human priority line $locale/$dept/$u")
          if (u >= 4) ck(h.contains(if (en) "High priority" else "优先级较高"), "human high-prio hint $locale/$dept/$u")
          else ck(!h.contains(if (en) "High priority" else "优先级较高"), "human no-prio hint $locale/$dept/$u")
        }
      }
    }
    // 未知部门回退「其他」段
    val fb = ReplyTemplates.autoReply(locale, "不存在的部门", 3, false)
    val other = ReplyTemplates.autoReply(locale, "其他", 3, false)
    ck(fb == other, "unknown dept fallback $locale")
    // 紧急度越界钳制 1..5
    ck(ReplyTemplates.autoReply(locale, "技术支持", 9, false).contains(if (en) toneEn[5]!! else toneZh[5]!!), "urgency clamp high $locale")
    ck(ReplyTemplates.autoReply(locale, "技术支持", 0, false).contains(if (en) toneEn[1]!! else toneZh[1]!!), "urgency clamp low $locale")
  }
  println("REPLY_COVER PASS=$pass FAIL=$fail")
  if (fail > 0) kotlin.system.exitProcess(1)
}
