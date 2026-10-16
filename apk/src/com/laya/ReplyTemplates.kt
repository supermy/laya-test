package com.laya

/**
 * 客服模型(ticket/multi 三问)自动回复模板与人工话术:
 * 分层拼接——开头(按部门)+ 语气/时效(按紧急度)+ 转人工段(按 q2)+ 结尾,
 * 组合天然 100% 覆盖模型输出空间(6部门 × 5紧急度 × 2升级)。
 * 纯 Kotlin 无 Context 依赖(JVM 单测直跑);zh/en 双语。
 * 部门键与 DecisionCore.builtinDefs 的 ticket/multi q0 criteria 一致;未知键回退「其他」。
 *
 * 模板引擎(2026-10-16 重构,与 service/reply-templates.mjs SPEC 表同构):
 * 文案选择全部走声明式 SPEC 表 + 单一 render(),无逐段 when/if-else。
 *   SPEC: (task, kind, key) → zh/en 全量默认文案(唯一来源);
 *   render(task, locale, kind, key, params, fallbackKey):
 *     DB 覆盖([source] 注入,Db.init 时挂入) → SPEC 默认(key 未命中试 fallbackKey;task 维度支持 '*') → 空串;
 *     params 做 {k} 占位符替换。
 * 业务分支(escalate 等)保留在组装函数里 —— 那是业务逻辑,不是文案选择。
 */
object ReplyTemplates {
  val DEPTS = listOf("账单计费", "技术支持", "销售咨询", "退换货", "故障维护", "其他")

  /** 模板覆盖查找:fn(task, locale, kind, key) -> 覆盖文案 | null。Db.init 注入;单测可注入 Map 实现 */
  fun interface TemplateSource { fun get(task: String, locale: String, kind: String, key: String): String? }

  @Volatile var source: TemplateSource? = null

  // ---- SPEC:全量默认文案(与 service/reply-templates.mjs SPEC_LIST 逐条对齐;改文案两处同步) ----
  private class Seg(val task: String, val kind: String, val key: String, val zh: String, val en: String)
  private fun s(task: String, kind: String, key: String, zh: String, en: String) = Seg(task, kind, key, zh, en)
  private val SPEC: Map<String, Seg> = listOf(
    // ---- ticket/multi 自动回复 ----
    s("ticket", "auto_header", "", "您好,感谢来信。", "Hello, thank you for reaching out."),
    s("ticket", "auto_open", "账单计费", "您反馈的账单问题已记录,我们将核对你的扣款记录与订单明细。", "Your billing issue has been logged. We will review your payment records and order details."),
    s("ticket", "auto_open", "技术支持", "您反馈的技术问题已记录,请提供设备型号、应用版本与问题截图,便于快速定位。", "Your technical issue has been logged. Please provide device model, app version and screenshots to help us locate the problem."),
    s("ticket", "auto_open", "销售咨询", "您的产品咨询已记录,顾问将根据您的需求提供方案与报价。", "Your inquiry has been logged. Our consultant will provide a tailored plan and quote."),
    s("ticket", "auto_open", "退换货", "您的退换货申请已记录,请保留好商品与包装,我们将尽快为您办理。", "Your return/exchange request has been logged. Please keep the item and packaging; we will process it shortly."),
    s("ticket", "auto_open", "故障维护", "您反馈的服务故障已记录,我们正在排查,恢复后将第一时间通知您。", "The service outage you reported has been logged. We are investigating and will notify you once restored."),
    s("ticket", "auto_open", "其他", "您的反馈已记录,我们将安排专人跟进处理。", "Your feedback has been logged and will be handled by a dedicated agent."),
    s("ticket", "auto_escalate", "", "您的问题需要人工坐席跟进,已为您转接人工处理,请留意后续专属回复。", "Your case requires a human agent and has been escalated; please watch for a personal follow-up."),
    s("ticket", "auto_tone", "5", "该问题已按【紧急】处理,我们承诺 1 小时内响应。", "This issue is marked URGENT and we commit to respond within 1 hour."),
    s("ticket", "auto_tone", "4", "该问题已按【高】优先级处理,承诺 4 小时内响应。", "This issue is set to HIGH priority with a response within 4 hours."),
    s("ticket", "auto_tone", "3", "该问题已按【中】优先级处理,预计 24 小时内回复。", "This issue is set to MEDIUM priority with a reply expected within 24 hours."),
    s("ticket", "auto_tone", "2", "该问题已按【低】优先级处理,预计 72 小时内回复。", "This issue is set to LOW priority with a reply expected within 72 hours."),
    s("ticket", "auto_tone", "1", "该问题已登记,预计一周内回复。", "This issue has been registered; a reply is expected within a week."),
    s("ticket", "auto_footer", "", "如有补充信息,请直接回复本邮件。\n(本邮件由智能客服助手自动发送)", "If you have more information, simply reply to this email.\n(This message was sent automatically by the assistant.)"),
    // ---- ticket 人工话术 ----
    s("ticket", "script_header", "", "【人工话术 · 建议稿】", "[Human script · draft]"),
    s("ticket", "human_open", "账单计费", "非常抱歉给您带来扣款困扰,我已为您核对账单明细,请稍等。", "I am very sorry about the billing trouble; I have pulled up your billing details."),
    s("ticket", "human_open", "技术支持", "很抱歉影响您的使用,请协助提供设备型号与截图,我来跟进排查。", "Sorry for the disruption; please share the device model and screenshots and I will investigate."),
    s("ticket", "human_open", "销售咨询", "感谢您的关注,我根据您的需求为您介绍合适的方案。", "Thanks for your interest; let me walk you through the plans that fit your needs."),
    s("ticket", "human_open", "退换货", "抱歉给您带来不便,我来为您办理退换货手续。", "Sorry for the inconvenience; I will process the return/exchange for you."),
    s("ticket", "human_open", "故障维护", "非常抱歉,故障已同步抢修,我来为您跟进恢复进度。", "We are truly sorry; the outage is being fixed and I will keep you updated."),
    s("ticket", "human_open", "其他", "感谢您的反馈,由我为您跟进处理。", "Thanks for your feedback; I will follow up with you personally."),
    s("ticket", "human_confirm", "", "处理完成后我会第一时间与您确认,请放心。", "I will confirm with you as soon as it is resolved."),
    s("ticket", "human_escalate_note", "", "※ 该工单优先级较高,建议优先跟进。", "* High priority ticket; suggest handling first."),
    // ---- ugc ----
    s("ugc", "ugc_header", "", "您好,该内容已完成机器审核。", "Hello, the content has completed automated review."),
    s("ugc", "ugc_open", "正常", "该内容已通过机器审核,未发现违规风险,可正常展示。", "The content has passed automated review; no violations detected and it can be published."),
    s("ugc", "ugc_open", "广告导流", "该内容涉嫌广告导流,已按社区规则限流并挂起展示。", "The content is suspected of ad diversion and has been throttled and held per community rules."),
    s("ugc", "ugc_open", "其他", "该内容涉嫌「{verdict}」违规,已自动拦截下架。", "The content is suspected of violating rules (\"{verdict}\") and has been automatically removed."),
    s("ugc", "ugc_escalate", "", "该内容需要人工复核,已转交审核专员处理,请留意复核结论。", "This content requires human review and has been escalated to a specialist; please await the review conclusion."),
    s("ugc", "ugc_sev", "high", "违规严重度为 {severity}/5,已同步留存证据并纳入风控名单。", "Violation severity is {severity}/5; evidence has been preserved and added to the risk list."),
    s("ugc", "ugc_sev", "low", "违规严重度为 {severity}/5,将在 24 小时内自动完成复核归档。", "Violation severity is {severity}/5; automated re-check will be archived within 24 hours."),
    s("ugc", "ugc_footer", "", "(本结果由智能内容审核助手自动生成)", "(This result was generated automatically by the content moderation assistant.)"),
    s("ugc", "ugc_script_header", "", "【人工复核 · 建议稿】", "[Human review · draft]"),
    s("ugc", "ugc_script_tips", "", "复核建议: 请核对判定与严重度是否准确,并补充处置依据。", "Review tips: verify the verdict and severity, and document the action taken."),
    // ---- agent ----
    s("agent", "agent_header", "", "您好,您的请求已完成智能路由。", "Hello, your request has been routed by the intelligent dispatcher."),
    s("agent", "agent_escalate", "", "该任务涉及多实体或多约束,已转交人工坐席拆解处理,请留意后续进展。", "The task involves multiple entities or constraints and has been escalated to a human agent for decomposition."),
    s("agent", "agent_route", "", "将交由「{route}」工作流处理。", "It will be handled by the \"{route}\" workflow."),
    s("agent", "agent_complex", "", "任务复杂度为 {complexity}/5({name}),工作流将按步骤执行。", "Task complexity is {complexity}/5; the workflow will execute step by step."),
    s("agent", "agent_footer", "", "(本路由结果由智能 Agent 调度助手自动生成)", "(This routing result was generated automatically by the agent dispatcher.)"),
    s("agent", "agent_script_header", "", "【任务拆解 · 建议稿】", "[Task decomposition · draft]"),
    s("agent", "agent_script_tips", "", "拆解建议: 先识别实体与约束,再拆分步骤并逐条确认执行顺序。", "Tips: identify entities and constraints first, then split into steps and confirm the order."),
    // ---- risk ----
    s("risk", "risk_header", "", "您好,该请求已完成风控前置研判。", "Hello, this request has completed pre-screening risk control."),
    s("risk", "risk_strength", "", "风险信号强度 {strength}/5,主要风险类型:「{riskType}」。", "Risk signal strength is {strength}/5; main risk type: \"{riskType}\"."),
    s("risk", "risk_action", "strong", "已触发强风控策略,本次请求将被拦截并转人工复核。", "Strong risk-control policy triggered; the request is blocked pending human review."),
    s("risk", "risk_action", "mid", "已触发加强验证流程,请配合完成二次核验。", "Enhanced verification triggered; please complete the secondary check."),
    s("risk", "risk_action", "normal", "未发现显著风险,按常规流程放行。", "No significant risk detected; proceeding with the standard flow."),
    s("risk", "risk_footer", "", "(本结果由智能风控前置助手自动生成)", "(This result was generated automatically by the risk-control assistant.)"),
    s("risk", "risk_script_header", "", "【风控复核 · 建议稿】", "[Risk review · draft]"),
    s("risk", "risk_script_tips", "", "复核建议: 核实风险类型与强度,确认拦截/放行决策并留痕。", "Tips: verify risk type and strength, confirm the block/allow decision and keep an audit trail."),
    // ---- 通用兜底(task='*' 任意业务;APK 侧结论为预拼字符串 generic_conclusion,Node 侧按 choice/score/noul 分段) ----
    s("*", "generic_header", "", "您好,「{task}」智能研判已完成。", "Hello, the \"{task}\" decision has been completed."),
    s("*", "generic_none", "", "模型未产出有效结论,建议人工跟进。", "The model produced no valid conclusion; manual follow-up is suggested."),
    s("*", "generic_conclusion", "", "研判结论:{conclusion}。", "Conclusion: {conclusion}."),
    s("*", "generic_escalate", "", "该结果需要人工确认,已转交专人跟进处理。", "This result requires human confirmation and has been escalated for follow-up."),
    s("*", "generic_auto", "", "结论已按策略自动执行。", "The conclusion has been applied automatically per policy."),
    s("*", "generic_footer", "", "(本结果由智能决策助手自动生成)", "(This result was generated automatically by the decision assistant.)"),
    s("*", "generic_script_header", "", "【人工跟进 · 建议稿】", "[Manual follow-up · draft]"),
    s("*", "generic_script_tips", "", "跟进建议: 核对模型结论与实际业务状态,处理完成后记录结论与依据。", "Tips: check the model conclusion against the actual state, then record the outcome and rationale."),
  ).associateBy { "${it.task}|${it.kind}|${it.key}" }

  /** 引擎唯一入口:DB 覆盖 → SPEC 默认(key 未命中试 fallbackKey;task 维度支持 '*')→ 空串;{k} 占位符替换 */
  private fun render(task: String, locale: String, kind: String, key: String,
                     params: Map<String, Any?>? = null, fallbackKey: String? = null): String {
    source?.get(task, locale, kind, key)?.let { return subst(it, params) }
    val get = { t: String, k: String -> SPEC["$t|$kind|$k"] }
    val seg = get(task, key) ?: fallbackKey?.let { get(task, it) }
        ?: get("*", key) ?: fallbackKey?.let { get("*", it) }
    return subst(if (locale == "en") seg?.en ?: "" else seg?.zh ?: "", params)
  }

  private fun subst(text: String, params: Map<String, Any?>?): String =
    if (params == null) text else params.entries.fold(text) { acc, (k, v) -> acc.replace("{$k}", v?.toString() ?: "") }

  // ---- 固定标签(非模板段) ----
  private fun seg(locale: String, zh: String, en: String) = if (locale == "en") en else zh

  /** 自动回复正文(q2=是 时输出"已转人工"模板,不再承诺 SLA) */
  fun autoReply(locale: String, dept: String, urgency: Int, escalate: Boolean): String =
    listOf(
      render("ticket", locale, "auto_header", ""),
      openSeg(locale, dept),
      if (escalate) render("ticket", locale, "auto_escalate", "") else toneSeg(locale, urgency),
      render("ticket", locale, "auto_footer", ""),
    ).joinToString("\n")

  /** 人工坐席话术建议稿(复述客户原文 + 按部门/紧急度的跟进话术) */
  fun humanScript(locale: String, dept: String, urgency: Int, userText: String): String {
    val L = mutableListOf(
      render("ticket", locale, "script_header", ""),
      seg(locale, "客户优先级: ", "Customer priority: ") + urgency.coerceIn(1, 5) + "/5",
      seg(locale, "客户原文:", "Customer message:") + " " + userText,
      seg(locale, "建议回复:", "Suggested reply:") + "\n" + humanOpenSeg(locale, dept) + " " + render("ticket", locale, "human_confirm", ""),
    )
    if (urgency >= 4) L.add(render("ticket", locale, "human_escalate_note", ""))
    return L.joinToString("\n")
  }

  // ---- 段落(对外保留 openSeg/humanOpenSeg 供测试;全部一行渲染) ----

  internal fun openSeg(locale: String, dept: String): String = render("ticket", locale, "auto_open", dept, fallbackKey = "其他")

  private fun toneSeg(locale: String, urgency: Int): String =
    render("ticket", locale, "auto_tone", urgency.coerceIn(1, 5).toString())

  internal fun humanOpenSeg(locale: String, dept: String): String = render("ticket", locale, "human_open", dept, fallbackKey = "其他")

  // ---- ugc 内容审核(q0 审核判定 / q1 违规严重度1-5 / q2 人工复核) ----

  private fun ugcOpenSeg(locale: String, verdict: String): String =
    if (verdict == "正常" || verdict == "广告导流") render("ugc", locale, "ugc_open", verdict)
    else render("ugc", locale, "ugc_open", "其他", mapOf("verdict" to verdict))

  fun ugcReply(locale: String, verdict: String, severity: Int, escalate: Boolean): String {
    val L = mutableListOf(render("ugc", locale, "ugc_header", ""), ugcOpenSeg(locale, verdict))
    if (escalate) L.add(render("ugc", locale, "ugc_escalate", ""))
    else if (verdict != "正常") L.add(render("ugc", locale, "ugc_sev", if (severity >= 3) "high" else "low", mapOf("severity" to severity)))
    L.add(render("ugc", locale, "ugc_footer", ""))
    return L.joinToString("\n")
  }

  fun ugcScript(locale: String, verdict: String, severity: Int, userText: String): String =
    listOf(
      render("ugc", locale, "ugc_script_header", ""),
      seg(locale, "违规严重度: ", "Violation severity: ") + severity + "/5",
      seg(locale, "审核判定: ", "Moderation verdict: ") + verdict,
      seg(locale, "内容原文:", "Content:") + " " + userText,
      render("ugc", locale, "ugc_script_tips", ""),
    ).joinToString("\n")

  // ---- agent 工作流路由(q0 路由 / q1 复杂度1-5 / q2 任务拆解) ----

  fun agentReply(locale: String, route: String, complexity: Int, escalate: Boolean): String {
    val L = mutableListOf(render("agent", locale, "agent_header", ""))
    if (escalate) L.add(render("agent", locale, "agent_escalate", ""))
    else {
      val c = complexity.coerceIn(1, 5)
      val name = listOf("", "一句话", "简单", "中等", "复杂", "极复杂")[c]
      L.add(render("agent", locale, "agent_route", "", mapOf("route" to route)))
      L.add(render("agent", locale, "agent_complex", "", mapOf("complexity" to c, "name" to name)))
    }
    L.add(render("agent", locale, "agent_footer", ""))
    return L.joinToString("\n")
  }

  fun agentScript(locale: String, route: String, complexity: Int, userText: String): String =
    listOf(
      render("agent", locale, "agent_script_header", ""),
      seg(locale, "任务复杂度: ", "Task complexity: ") + complexity + "/5",
      seg(locale, "原始路由: ", "Initial routing: ") + route,
      seg(locale, "任务原文:", "Task message:") + " " + userText,
      render("agent", locale, "agent_script_tips", ""),
    ).joinToString("\n")

  // ---- risk 风控前置(q0 风险信号强度1-5 / q1 主要风险类型 / q2 人工复核) ----

  fun riskReply(locale: String, strength: Int, riskType: String, escalate: Boolean): String {
    val actionKey = if (escalate || strength >= 4) "strong" else if (strength == 3) "mid" else "normal"
    return listOf(
      render("risk", locale, "risk_header", ""),
      render("risk", locale, "risk_strength", "", mapOf("strength" to strength, "riskType" to riskType)),
      render("risk", locale, "risk_action", actionKey),
      render("risk", locale, "risk_footer", ""),
    ).joinToString("\n")
  }

  fun riskScript(locale: String, strength: Int, riskType: String, userText: String): String =
    listOf(
      render("risk", locale, "risk_script_header", ""),
      seg(locale, "风险信号强度: ", "Risk signal strength: ") + strength + "/5",
      seg(locale, "主要风险类型: ", "Main risk type: ") + riskType,
      seg(locale, "请求原文:", "Request message:") + " " + userText,
      render("risk", locale, "risk_script_tips", ""),
    ).joinToString("\n")

  // ---- 通用兜底(news/stock/未知业务:取首个 choice/score/noul) ----

  fun genericReply(locale: String, task: String, conclusion: String?, escalate: Boolean): String {
    val conclusionSeg = if (conclusion == null) render(task, locale, "generic_none", "")
    else render(task, locale, "generic_conclusion", "", mapOf("conclusion" to conclusion))
    return listOf(
      render(task, locale, "generic_header", "", mapOf("task" to task)),
      conclusionSeg,
      if (escalate) render(task, locale, "generic_escalate", "") else render(task, locale, "generic_auto", ""),
      render(task, locale, "generic_footer", ""),
    ).joinToString("\n")
  }

  fun genericScript(locale: String, task: String, conclusion: String?, userText: String): String {
    val L = mutableListOf(render(task, locale, "generic_script_header", ""))
    if (conclusion != null) L.add(seg(locale, "研判结论: ", "Conclusion: ") + conclusion)
    L.add(seg(locale, "原始输入:", "Original input:") + " " + userText)
    L.add(render(task, locale, "generic_script_tips", ""))
    return L.joinToString("\n")
  }
}
