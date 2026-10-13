package com.laya

/**
 * 客服模型(ticket/multi 三问)自动回复模板与人工话术:
 * 分层拼接——开头(按部门)+ 语气/时效(按紧急度)+ 转人工段(按 q2)+ 结尾,
 * 组合天然 100% 覆盖模型输出空间(6部门 × 5紧急度 × 2升级)。
 * 纯 Kotlin 无 Context 依赖(JVM 单测直跑);zh/en 双语。
 * 部门键与 DecisionCore.builtinDefs 的 ticket/multi q0 criteria 一致;未知键回退「其他」。
 */
object ReplyTemplates {
  val DEPTS = listOf("账单计费", "技术支持", "销售咨询", "退换货", "故障维护", "其他")

  /** 自动回复正文(q2=是 时输出"已转人工"模板,不再承诺 SLA) */
  fun autoReply(locale: String, dept: String, urgency: Int, escalate: Boolean): String {
    val sb = StringBuilder(seg(locale, "您好,感谢来信。", "Hello, thank you for reaching out.")).append('\n')
    sb.append(openSeg(locale, dept)).append('\n')
    if (escalate) {
      sb.append(seg(locale,
        "您的问题需要人工坐席跟进,已为您转接人工处理,请留意后续专属回复。",
        "Your case requires a human agent and has been escalated; please watch for a personal follow-up.")).append('\n')
    } else {
      sb.append(toneSeg(locale, urgency)).append('\n')
    }
    sb.append(seg(locale,
      "如有补充信息,请直接回复本邮件。\n(本邮件由智能客服助手自动发送)",
      "If you have more information, simply reply to this email.\n(This message was sent automatically by the assistant.)"))
    return sb.toString()
  }

  /** 人工坐席话术建议稿(复述客户原文 + 按部门/紧急度的跟进话术) */
  fun humanScript(locale: String, dept: String, urgency: Int, userText: String): String {
    val sb = StringBuilder(seg(locale, "【人工话术 · 建议稿】", "[Human script · draft]")).append('\n')
    sb.append(seg(locale, "客户优先级: ", "Customer priority: ")).append(urgency).append("/5\n")
    sb.append(seg(locale, "客户原文:", "Customer message:")).append(' ').append(userText).append('\n')
    sb.append(seg(locale, "建议回复:", "Suggested reply:")).append('\n')
    sb.append(humanOpenSeg(locale, dept)).append(' ')
    sb.append(seg(locale, "处理完成后我会第一时间与您确认,请放心。", "I will confirm with you as soon as it is resolved."))
    if (urgency >= 4) sb.append('\n').append(seg(locale,
      "※ 该工单优先级较高,建议优先跟进。",
      "* High priority ticket; suggest handling first."))
    return sb.toString()
  }

  // ---- 段落 ----

  private fun seg(locale: String, zh: String, en: String) = if (locale == "en") en else zh

  internal fun openSeg(locale: String, dept: String): String = when (dept) {
    "账单计费" -> seg(locale,
      "您反馈的账单问题已记录,我们将核对你的扣款记录与订单明细。",
      "Your billing issue has been logged. We will review your payment records and order details.")
    "技术支持" -> seg(locale,
      "您反馈的技术问题已记录,请提供设备型号、应用版本与问题截图,便于快速定位。",
      "Your technical issue has been logged. Please provide device model, app version and screenshots to help us locate the problem.")
    "销售咨询" -> seg(locale,
      "您的产品咨询已记录,顾问将根据您的需求提供方案与报价。",
      "Your inquiry has been logged. Our consultant will provide a tailored plan and quote.")
    "退换货" -> seg(locale,
      "您的退换货申请已记录,请保留好商品与包装,我们将尽快为您办理。",
      "Your return/exchange request has been logged. Please keep the item and packaging; we will process it shortly.")
    "故障维护" -> seg(locale,
      "您反馈的服务故障已记录,我们正在排查,恢复后将第一时间通知您。",
      "The service outage you reported has been logged. We are investigating and will notify you once restored.")
    else -> seg(locale, // 其他 + 未知键回退
      "您的反馈已记录,我们将安排专人跟进处理。",
      "Your feedback has been logged and will be handled by a dedicated agent.")
  }

  /** 语气/时效段:1=一周 2=72小时 3=24小时 4=4小时 5=1小时(测试按这些关键词断言) */
  private fun toneSeg(locale: String, urgency: Int): String {
    val u = urgency.coerceIn(1, 5)
    return when (u) {
      5 -> seg(locale, "该问题已按【紧急】处理,我们承诺 1 小时内响应。",
        "This issue is marked URGENT and we commit to respond within 1 hour.")
      4 -> seg(locale, "该问题已按【高】优先级处理,承诺 4 小时内响应。",
        "This issue is set to HIGH priority with a response within 4 hours.")
      3 -> seg(locale, "该问题已按【中】优先级处理,预计 24 小时内回复。",
        "This issue is set to MEDIUM priority with a reply expected within 24 hours.")
      2 -> seg(locale, "该问题已按【低】优先级处理,预计 72 小时内回复。",
        "This issue is set to LOW priority with a reply expected within 72 hours.")
      else -> seg(locale, "该问题已登记,预计一周内回复。",
        "This issue has been registered; a reply is expected within a week.")
    }
  }

  internal fun humanOpenSeg(locale: String, dept: String): String = when (dept) {
    "账单计费" -> seg(locale,
      "非常抱歉给您带来扣款困扰,我已为您核对账单明细,请稍等。",
      "I am very sorry about the billing trouble; I have pulled up your billing details.")
    "技术支持" -> seg(locale,
      "很抱歉影响您的使用,请协助提供设备型号与截图,我来跟进排查。",
      "Sorry for the disruption; please share the device model and screenshots and I will investigate.")
    "销售咨询" -> seg(locale,
      "感谢您的关注,我根据您的需求为您介绍合适的方案。",
      "Thanks for your interest; let me walk you through the plans that fit your needs.")
    "退换货" -> seg(locale,
      "抱歉给您带来不便,我来为您办理退换货手续。",
      "Sorry for the inconvenience; I will process the return/exchange for you.")
    "故障维护" -> seg(locale,
      "非常抱歉,故障已同步抢修,我来为您跟进恢复进度。",
      "We are truly sorry; the outage is being fixed and I will keep you updated.")
    else -> seg(locale,
      "感谢您的反馈,由我为您跟进处理。",
      "Thanks for your feedback; I will follow up with you personally.")
  }
}
