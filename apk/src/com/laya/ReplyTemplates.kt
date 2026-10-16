package com.laya

/**
 * 客服模型(ticket/multi 三问)自动回复模板与人工话术:
 * 分层拼接——开头(按部门)+ 语气/时效(按紧急度)+ 转人工段(按 q2)+ 结尾,
 * 组合天然 100% 覆盖模型输出空间(6部门 × 5紧急度 × 2升级)。
 * 纯 Kotlin 无 Context 依赖(JVM 单测直跑);zh/en 双语。
 * 部门键与 DecisionCore.builtinDefs 的 ticket/multi q0 criteria 一致;未知键回退「其他」。
 *
 * 模板覆盖(2026-10-16,与 service/reply-templates.mjs kind/key 约定一致):
 * [source] 注入 DB 查找(Db.init 时注入),有覆盖行用覆盖,否则内置默认;未注入(单测)直接内置。
 * kind/key 维度:ticket(auto_header/auto_open/auto_tone/auto_escalate/auto_footer/script_header/
 * human_open/human_confirm/human_escalate_note)、ugc、agent、risk、generic 各自前缀段。
 */
object ReplyTemplates {
  val DEPTS = listOf("账单计费", "技术支持", "销售咨询", "退换货", "故障维护", "其他")

  /** 模板覆盖查找:fn(task, locale, kind, key) -> 覆盖文案 | null。Db.init 注入;单测可注入 Map 实现 */
  fun interface TemplateSource { fun get(task: String, locale: String, kind: String, key: String): String? }

  @Volatile var source: TemplateSource? = null

  /** 段落取值:覆盖 → 内置默认;params 做 {k} 占位符替换 */
  private fun tpl(task: String, locale: String, kind: String, key: String, zh: String, en: String,
                  params: Map<String, Any?>? = null): String {
    var text = source?.get(task, locale, kind, key) ?: (if (locale == "en") en else zh)
    if (params != null) for ((k, v) in params) text = text.replace("{$k}", v?.toString() ?: "")
    return text
  }

  /** 自动回复正文(q2=是 时输出"已转人工"模板,不再承诺 SLA) */
  fun autoReply(locale: String, dept: String, urgency: Int, escalate: Boolean): String {
    val sb = StringBuilder(tpl("ticket", locale, "auto_header", "", "您好,感谢来信。", "Hello, thank you for reaching out.")).append('\n')
    sb.append(openSeg(locale, dept)).append('\n')
    if (escalate) {
      sb.append(tpl("ticket", locale, "auto_escalate", "",
        "您的问题需要人工坐席跟进,已为您转接人工处理,请留意后续专属回复。",
        "Your case requires a human agent and has been escalated; please watch for a personal follow-up.")).append('\n')
    } else {
      sb.append(toneSeg(locale, urgency)).append('\n')
    }
    sb.append(tpl("ticket", locale, "auto_footer", "",
      "如有补充信息,请直接回复本邮件。\n(本邮件由智能客服助手自动发送)",
      "If you have more information, simply reply to this email.\n(This message was sent automatically by the assistant.)"))
    return sb.toString()
  }

  /** 人工坐席话术建议稿(复述客户原文 + 按部门/紧急度的跟进话术) */
  fun humanScript(locale: String, dept: String, urgency: Int, userText: String): String {
    val sb = StringBuilder(tpl("ticket", locale, "script_header", "", "【人工话术 · 建议稿】", "[Human script · draft]")).append('\n')
    sb.append(seg(locale, "客户优先级: ", "Customer priority: ")).append(urgency).append("/5\n")
    sb.append(seg(locale, "客户原文:", "Customer message:")).append(' ').append(userText).append('\n')
    sb.append(seg(locale, "建议回复:", "Suggested reply:")).append('\n')
    sb.append(humanOpenSeg(locale, dept)).append(' ')
    sb.append(tpl("ticket", locale, "human_confirm", "", "处理完成后我会第一时间与您确认,请放心。", "I will confirm with you as soon as it is resolved."))
    if (urgency >= 4) sb.append('\n').append(tpl("ticket", locale, "human_escalate_note", "",
      "※ 该工单优先级较高,建议优先跟进。",
      "* High priority ticket; suggest handling first."))
    return sb.toString()
  }

  // ---- 段落 ----

  private fun seg(locale: String, zh: String, en: String) = if (locale == "en") en else zh

  internal fun openSeg(locale: String, dept: String): String = when (dept) {
    "账单计费" -> tpl("ticket", locale, "auto_open", dept,
      "您反馈的账单问题已记录,我们将核对你的扣款记录与订单明细。",
      "Your billing issue has been logged. We will review your payment records and order details.")
    "技术支持" -> tpl("ticket", locale, "auto_open", dept,
      "您反馈的技术问题已记录,请提供设备型号、应用版本与问题截图,便于快速定位。",
      "Your technical issue has been logged. Please provide device model, app version and screenshots to help us locate the problem.")
    "销售咨询" -> tpl("ticket", locale, "auto_open", dept,
      "您的产品咨询已记录,顾问将根据您的需求提供方案与报价。",
      "Your inquiry has been logged. Our consultant will provide a tailored plan and quote.")
    "退换货" -> tpl("ticket", locale, "auto_open", dept,
      "您的退换货申请已记录,请保留好商品与包装,我们将尽快为您办理。",
      "Your return/exchange request has been logged. Please keep the item and packaging; we will process it shortly.")
    "故障维护" -> tpl("ticket", locale, "auto_open", dept,
      "您反馈的服务故障已记录,我们正在排查,恢复后将第一时间通知您。",
      "The service outage you reported has been logged. We are investigating and will notify you once restored.")
    else -> tpl("ticket", locale, "auto_open", dept, // 其他 + 未知键回退
      "您的反馈已记录,我们将安排专人跟进处理。",
      "Your feedback has been logged and will be handled by a dedicated agent.")
  }

  /** 语气/时效段:1=一周 2=72小时 3=24小时 4=4小时 5=1小时(测试按这些关键词断言) */
  private fun toneSeg(locale: String, urgency: Int): String {
    val u = urgency.coerceIn(1, 5)
    return when (u) {
      5 -> tpl("ticket", locale, "auto_tone", "5", "该问题已按【紧急】处理,我们承诺 1 小时内响应。",
        "This issue is marked URGENT and we commit to respond within 1 hour.")
      4 -> tpl("ticket", locale, "auto_tone", "4", "该问题已按【高】优先级处理,承诺 4 小时内响应。",
        "This issue is set to HIGH priority with a response within 4 hours.")
      3 -> tpl("ticket", locale, "auto_tone", "3", "该问题已按【中】优先级处理,预计 24 小时内回复。",
        "This issue is set to MEDIUM priority with a reply expected within 24 hours.")
      2 -> tpl("ticket", locale, "auto_tone", "2", "该问题已按【低】优先级处理,预计 72 小时内回复。",
        "This issue is set to LOW priority with a reply expected within 72 hours.")
      else -> tpl("ticket", locale, "auto_tone", "1", "该问题已登记,预计一周内回复。",
        "This issue has been registered; a reply is expected within a week.")
    }
  }

  internal fun humanOpenSeg(locale: String, dept: String): String = when (dept) {
    "账单计费" -> tpl("ticket", locale, "human_open", dept,
      "非常抱歉给您带来扣款困扰,我已为您核对账单明细,请稍等。",
      "I am very sorry about the billing trouble; I have pulled up your billing details.")
    "技术支持" -> tpl("ticket", locale, "human_open", dept,
      "很抱歉影响您的使用,请协助提供设备型号与截图,我来跟进排查。",
      "Sorry for the disruption; please share the device model and screenshots and I will investigate.")
    "销售咨询" -> tpl("ticket", locale, "human_open", dept,
      "感谢您的关注,我根据您的需求为您介绍合适的方案。",
      "Thanks for your interest; let me walk you through the plans that fit your needs.")
    "退换货" -> tpl("ticket", locale, "human_open", dept,
      "抱歉给您带来不便,我来为您办理退换货手续。",
      "Sorry for the inconvenience; I will process the return/exchange for you.")
    "故障维护" -> tpl("ticket", locale, "human_open", dept,
      "非常抱歉,故障已同步抢修,我来为您跟进恢复进度。",
      "We are truly sorry; the outage is being fixed and I will keep you updated.")
    else -> tpl("ticket", locale, "human_open", dept,
      "感谢您的反馈,由我为您跟进处理。",
      "Thanks for your feedback; I will follow up with you personally.")
  }

  // ---- ugc 内容审核(q0 审核判定 / q1 违规严重度1-5 / q2 人工复核) ----

  private fun ugcOpenSeg(locale: String, verdict: String): String = when (verdict) {
    "正常" -> tpl("ugc", locale, "ugc_open", "正常",
      "该内容已通过机器审核,未发现违规风险,可正常展示。",
      "The content has passed automated review; no violations detected and it can be published.")
    "广告导流" -> tpl("ugc", locale, "ugc_open", "广告导流",
      "该内容涉嫌广告导流,已按社区规则限流并挂起展示。",
      "The content is suspected of ad diversion and has been throttled and held per community rules.")
    else -> tpl("ugc", locale, "ugc_open", "其他", // 辱骂攻击/色情低俗/诈骗引流 + 未知判定
      "该内容涉嫌「$verdict」违规,已自动拦截下架。",
      "The content is suspected of violating rules (\"$verdict\") and has been automatically removed.",
      mapOf("verdict" to verdict))
  }

  fun ugcReply(locale: String, verdict: String, severity: Int, escalate: Boolean): String {
    val sb = StringBuilder(tpl("ugc", locale, "ugc_header", "", "您好,该内容已完成机器审核。", "Hello, the content has completed automated review.")).append('\n')
    sb.append(ugcOpenSeg(locale, verdict)).append('\n')
    if (escalate) {
      sb.append(tpl("ugc", locale, "ugc_escalate", "",
        "该内容需要人工复核,已转交审核专员处理,请留意复核结论。",
        "This content requires human review and has been escalated to a specialist; please await the review conclusion.")).append('\n')
    } else if (verdict != "正常") {
      sb.append(if (severity >= 3) tpl("ugc", locale, "ugc_sev", "high",
        "违规严重度为 $severity/5,已同步留存证据并纳入风控名单。",
        "Violation severity is $severity/5; evidence has been preserved and added to the risk list.",
        mapOf("severity" to severity))
      else tpl("ugc", locale, "ugc_sev", "low",
        "违规严重度为 $severity/5,将在 24 小时内自动完成复核归档。",
        "Violation severity is $severity/5; automated re-check will be archived within 24 hours.",
        mapOf("severity" to severity))).append('\n')
    }
    sb.append(tpl("ugc", locale, "ugc_footer", "",
      "(本结果由智能内容审核助手自动生成)",
      "(This result was generated automatically by the content moderation assistant.)"))
    return sb.toString()
  }

  fun ugcScript(locale: String, verdict: String, severity: Int, userText: String): String {
    val sb = StringBuilder(tpl("ugc", locale, "ugc_script_header", "", "【人工复核 · 建议稿】", "[Human review · draft]")).append('\n')
    sb.append(seg(locale, "违规严重度: ", "Violation severity: ")).append(severity).append("/5\n")
    sb.append(seg(locale, "审核判定: ", "Moderation verdict: ")).append(verdict).append('\n')
    sb.append(seg(locale, "内容原文:", "Content:")).append(' ').append(userText).append('\n')
    sb.append(tpl("ugc", locale, "ugc_script_tips", "",
      "复核建议: 请核对判定与严重度是否准确,并补充处置依据。",
      "Review tips: verify the verdict and severity, and document the action taken."))
    return sb.toString()
  }

  // ---- agent 工作流路由(q0 路由 / q1 复杂度1-5 / q2 任务拆解) ----

  fun agentReply(locale: String, route: String, complexity: Int, escalate: Boolean): String {
    val sb = StringBuilder(tpl("agent", locale, "agent_header", "", "您好,您的请求已完成智能路由。", "Hello, your request has been routed by the intelligent dispatcher.")).append('\n')
    if (escalate) {
      sb.append(tpl("agent", locale, "agent_escalate", "",
        "该任务涉及多实体或多约束,已转交人工坐席拆解处理,请留意后续进展。",
        "The task involves multiple entities or constraints and has been escalated to a human agent for decomposition.")).append('\n')
    } else {
      sb.append(tpl("agent", locale, "agent_route", "", "将交由「$route」工作流处理。", "It will be handled by the \"$route\" workflow.",
        mapOf("route" to route))).append('\n')
      val c = complexity.coerceIn(1, 5)
      val name = listOf("", "一句话", "简单", "中等", "复杂", "极复杂")[c]
      sb.append(tpl("agent", locale, "agent_complex", "", "任务复杂度为 $c/5($name),工作流将按步骤执行。",
        "Task complexity is $c/5; the workflow will execute step by step.",
        mapOf("complexity" to c, "name" to name))).append('\n')
    }
    sb.append(tpl("agent", locale, "agent_footer", "",
      "(本路由结果由智能 Agent 调度助手自动生成)",
      "(This routing result was generated automatically by the agent dispatcher.)"))
    return sb.toString()
  }

  fun agentScript(locale: String, route: String, complexity: Int, userText: String): String {
    val sb = StringBuilder(tpl("agent", locale, "agent_script_header", "", "【任务拆解 · 建议稿】", "[Task decomposition · draft]")).append('\n')
    sb.append(seg(locale, "任务复杂度: ", "Task complexity: ")).append(complexity).append("/5\n")
    sb.append(seg(locale, "原始路由: ", "Initial routing: ")).append(route).append('\n')
    sb.append(seg(locale, "任务原文:", "Task message:")).append(' ').append(userText).append('\n')
    sb.append(tpl("agent", locale, "agent_script_tips", "",
      "拆解建议: 先识别实体与约束,再拆分步骤并逐条确认执行顺序。",
      "Tips: identify entities and constraints first, then split into steps and confirm the order."))
    return sb.toString()
  }

  // ---- risk 风控前置(q0 风险信号强度1-5 / q1 主要风险类型 / q2 人工复核) ----

  fun riskReply(locale: String, strength: Int, riskType: String, escalate: Boolean): String {
    val sb = StringBuilder(tpl("risk", locale, "risk_header", "", "您好,该请求已完成风控前置研判。", "Hello, this request has completed pre-screening risk control.")).append('\n')
    sb.append(tpl("risk", locale, "risk_strength", "", "风险信号强度 $strength/5,主要风险类型:「$riskType」。",
      "Risk signal strength is $strength/5; main risk type: \"$riskType\".",
      mapOf("strength" to strength, "riskType" to riskType))).append('\n')
    sb.append(when {
      escalate || strength >= 4 -> tpl("risk", locale, "risk_action", "strong",
        "已触发强风控策略,本次请求将被拦截并转人工复核。",
        "Strong risk-control policy triggered; the request is blocked pending human review.")
      strength == 3 -> tpl("risk", locale, "risk_action", "mid",
        "已触发加强验证流程,请配合完成二次核验。",
        "Enhanced verification triggered; please complete the secondary check.")
      else -> tpl("risk", locale, "risk_action", "normal", "未发现显著风险,按常规流程放行。",
        "No significant risk detected; proceeding with the standard flow.")
    }).append('\n')
    sb.append(tpl("risk", locale, "risk_footer", "",
      "(本结果由智能风控前置助手自动生成)",
      "(This result was generated automatically by the risk-control assistant.)"))
    return sb.toString()
  }

  fun riskScript(locale: String, strength: Int, riskType: String, userText: String): String {
    val sb = StringBuilder(tpl("risk", locale, "risk_script_header", "", "【风控复核 · 建议稿】", "[Risk review · draft]")).append('\n')
    sb.append(seg(locale, "风险信号强度: ", "Risk signal strength: ")).append(strength).append("/5\n")
    sb.append(seg(locale, "主要风险类型: ", "Main risk type: ")).append(riskType).append('\n')
    sb.append(seg(locale, "请求原文:", "Request message:")).append(' ').append(userText).append('\n')
    sb.append(tpl("risk", locale, "risk_script_tips", "",
      "复核建议: 核实风险类型与强度,确认拦截/放行决策并留痕。",
      "Tips: verify risk type and strength, confirm the block/allow decision and keep an audit trail."))
    return sb.toString()
  }

  // ---- 通用兜底(news/stock/未知业务:取首个 choice/score/noul) ----

  fun genericReply(locale: String, task: String, conclusion: String?, escalate: Boolean): String {
    val sb = StringBuilder(tpl(task, locale, "generic_header", "", "您好,「$task」智能研判已完成。", "Hello, the \"$task\" decision has been completed.",
      mapOf("task" to task))).append('\n')
    sb.append(if (conclusion == null) tpl(task, locale, "generic_none", "",
      "模型未产出有效结论,建议人工跟进。",
      "The model produced no valid conclusion; manual follow-up is suggested.")
    else tpl(task, locale, "generic_conclusion", "", "研判结论:$conclusion。", "Conclusion: $conclusion.",
      mapOf("conclusion" to conclusion))).append('\n')
    sb.append(if (escalate) tpl(task, locale, "generic_escalate", "",
      "该结果需要人工确认,已转交专人跟进处理。",
      "This result requires human confirmation and has been escalated for follow-up.")
    else tpl(task, locale, "generic_auto", "", "结论已按策略自动执行。", "The conclusion has been applied automatically per policy.")).append('\n')
    sb.append(tpl(task, locale, "generic_footer", "",
      "(本结果由智能决策助手自动生成)",
      "(This result was generated automatically by the decision assistant.)"))
    return sb.toString()
  }

  fun genericScript(locale: String, task: String, conclusion: String?, userText: String): String {
    val sb = StringBuilder(tpl(task, locale, "generic_script_header", "", "【人工跟进 · 建议稿】", "[Manual follow-up · draft]")).append('\n')
    if (conclusion != null) sb.append(seg(locale, "研判结论: ", "Conclusion: ")).append(conclusion).append('\n')
    sb.append(seg(locale, "原始输入:", "Original input:")).append(' ').append(userText).append('\n')
    sb.append(tpl(task, locale, "generic_script_tips", "",
      "跟进建议: 核对模型结论与实际业务状态,处理完成后记录结论与依据。",
      "Tips: check the model conclusion against the actual state, then record the outcome and rationale."))
    return sb.toString()
  }
}
