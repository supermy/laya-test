// ReplyTemplates 100% 覆盖测试:6部门 × 5紧急度 × 2升级 × 2模式(自动/人工) × 2语言 = 120 组合逐项断言
// 加未知部门回退/紧急度钳制/DecisionCore.replyAppendix 升级条件。JVM 直跑: java Reply_testKt
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

  // ---- DecisionCore.replyAppendix(升级条件:q2 noul≥0.5 或 urgency≥4;非客服业务空串) ----
  fun answers(score: Int, noul: Double) = org.json.JSONObject(
    "{\"q0\":{\"type\":\"choice\",\"choice\":\"账单计费\"}," +
    "\"q1\":{\"type\":\"score\",\"score\":$score}," +
    "\"q2\":{\"type\":\"noul\",\"noul\":$noul}}")
  val hi = com.laya.DecisionCore.replyAppendix(null, "ticket", answers(5, 0.1), "T1")
  ck(hi.contains("【人工回复】") && hi.contains("优先级较高"), "urgency>=4 → human")
  val byNoul = com.laya.DecisionCore.replyAppendix(null, "ticket", answers(2, 0.9), "T2")
  ck(byNoul.contains("【人工回复】"), "noul>=0.5 → human")
  val auto = com.laya.DecisionCore.replyAppendix(null, "ticket", answers(2, 0.1), "T3")
  ck(auto.contains("【自动回复】") && !auto.contains("人工话术"), "low urgency+noul → auto only")
  // 全业务模板:非客服业务走通用兜底(noul≥0.5 → 人工跟进,否则自动回复)
  val gNews = com.laya.DecisionCore.replyAppendix(null, "news", answers(5, 0.9), "T4")
  ck(gNews.contains("【人工跟进】") && gNews.contains("「账单计费」"), "non-ticket escalate → generic human")
  val gNewsAuto = com.laya.DecisionCore.replyAppendix(null, "news", answers(2, 0.1), "T5")
  ck(gNewsAuto.contains("【自动回复】") && gNewsAuto.contains("研判结论"), "non-ticket low → generic auto")
  // ugc/agent/risk 业务专属模板(risk 问序与 ugc 相反:q0=强度 score,q1=类型 choice)
  fun qa(choice: String, score: Int, noul: Double) = org.json.JSONObject(
    "{\"q0\":{\"type\":\"choice\",\"choice\":\"$choice\"}," +
    "\"q1\":{\"type\":\"score\",\"score\":$score}," +
    "\"q2\":{\"type\":\"noul\",\"noul\":$noul}}")
  fun qr(strength: Int, choice: String, noul: Double) = org.json.JSONObject(
    "{\"q0\":{\"type\":\"score\",\"score\":$strength}," +
    "\"q1\":{\"type\":\"choice\",\"choice\":\"$choice\"}," +
    "\"q2\":{\"type\":\"noul\",\"noul\":$noul}}")
  val ugcAuto = com.laya.DecisionCore.replyAppendix(null, "ugc", qa("广告导流", 3, 0.1), "T6")
  ck(ugcAuto.contains("【自动回复】") && ugcAuto.contains("广告导流"), "ugc auto → verdict")
  val ugcHi = com.laya.DecisionCore.replyAppendix(null, "ugc", qa("色情低俗", 4, 0.1), "T7")
  ck(ugcHi.contains("【人工复核】"), "ugc severity>=4 → human")
  val agAuto = com.laya.DecisionCore.replyAppendix(null, "agent", qa("工具调用", 2, 0.1), "T8")
  ck(agAuto.contains("【自动回复】") && agAuto.contains("工具调用"), "agent auto → route")
  val agHi = com.laya.DecisionCore.replyAppendix(null, "agent", qa("转人工", 5, 0.1), "T9")
  ck(agHi.contains("【人工跟进】"), "agent route=转人工 → human")
  val rkAuto = com.laya.DecisionCore.replyAppendix(null, "risk", qr(2, "欺诈风险", 0.1), "T10")
  ck(rkAuto.contains("【自动回复】") && rkAuto.contains("欺诈风险"), "risk auto → type")
  val rkHi = com.laya.DecisionCore.replyAppendix(null, "risk", qr(5, "欺诈风险", 0.1), "T11")
  ck(rkHi.contains("【人工复核】"), "risk strength>=4 → human")

  println("REPLY_COVER PASS=$pass FAIL=$fail")
  if (fail > 0) kotlin.system.exitProcess(1)
}
