// 客服(ticket/multi)自动回复模板与人工话术 —— 移植自 apk/src/com/laya/ReplyTemplates.kt,
// 段落文案与升级条件保持一致(escalate = q2 noul≥0.5 或 紧急度≥4 兜底,语料 escalate 全 0)。
// 供 server.mjs 决策响应与 test-cs-mqtt.mjs 测试输出共用。

export const DEPTS = ["账单计费", "技术支持", "销售咨询", "退换货", "故障维护", "其他"];

const seg = (locale, zh, en) => (locale === "en" ? en : zh);

export function openSeg(locale, dept) {
  switch (dept) {
    case "账单计费": return seg(locale,
      "您反馈的账单问题已记录,我们将核对你的扣款记录与订单明细。",
      "Your billing issue has been logged. We will review your payment records and order details.");
    case "技术支持": return seg(locale,
      "您反馈的技术问题已记录,请提供设备型号、应用版本与问题截图,便于快速定位。",
      "Your technical issue has been logged. Please provide device model, app version and screenshots to help us locate the problem.");
    case "销售咨询": return seg(locale,
      "您的产品咨询已记录,顾问将根据您的需求提供方案与报价。",
      "Your inquiry has been logged. Our consultant will provide a tailored plan and quote.");
    case "退换货": return seg(locale,
      "您的退换货申请已记录,请保留好商品与包装,我们将尽快为您办理。",
      "Your return/exchange request has been logged. Please keep the item and packaging; we will process it shortly.");
    case "故障维护": return seg(locale,
      "您反馈的服务故障已记录,我们正在排查,恢复后将第一时间通知您。",
      "The service outage you reported has been logged. We are investigating and will notify you once restored.");
    default: return seg(locale, // 其他 + 未知键回退
      "您的反馈已记录,我们将安排专人跟进处理。",
      "Your feedback has been logged and will be handled by a dedicated agent.");
  }
}

/** 语气/时效段:1=一周 2=72小时 3=24小时 4=4小时 5=1小时 */
function toneSeg(locale, urgency) {
  const u = Math.min(5, Math.max(1, urgency));
  switch (u) {
    case 5: return seg(locale, "该问题已按【紧急】处理,我们承诺 1 小时内响应。",
      "This issue is marked URGENT and we commit to respond within 1 hour.");
    case 4: return seg(locale, "该问题已按【高】优先级处理,承诺 4 小时内响应。",
      "This issue is set to HIGH priority with a response within 4 hours.");
    case 3: return seg(locale, "该问题已按【中】优先级处理,预计 24 小时内回复。",
      "This issue is set to MEDIUM priority with a reply expected within 24 hours.");
    case 2: return seg(locale, "该问题已按【低】优先级处理,预计 72 小时内回复。",
      "This issue is set to LOW priority with a reply expected within 72 hours.");
    default: return seg(locale, "该问题已登记,预计一周内回复。",
      "This issue has been registered; a reply is expected within a week.");
  }
}

export function humanOpenSeg(locale, dept) {
  switch (dept) {
    case "账单计费": return seg(locale,
      "非常抱歉给您带来扣款困扰,我已为您核对账单明细,请稍等。",
      "I am very sorry about the billing trouble; I have pulled up your billing details.");
    case "技术支持": return seg(locale,
      "很抱歉影响您的使用,请协助提供设备型号与截图,我来跟进排查。",
      "Sorry for the disruption; please share the device model and screenshots and I will investigate.");
    case "销售咨询": return seg(locale,
      "感谢您的关注,我根据您的需求为您介绍合适的方案。",
      "Thanks for your interest; let me walk you through the plans that fit your needs.");
    case "退换货": return seg(locale,
      "抱歉给您带来不便,我来为您办理退换货手续。",
      "Sorry for the inconvenience; I will process the return/exchange for you.");
    case "故障维护": return seg(locale,
      "非常抱歉,故障已同步抢修,我来为您跟进恢复进度。",
      "We are truly sorry; the outage is being fixed and I will keep you updated.");
    default: return seg(locale,
      "感谢您的反馈,由我为您跟进处理。",
      "Thanks for your feedback; I will follow up with you personally.");
  }
}

/** 自动回复正文(q2=是 时输出"已转人工"模板,不再承诺 SLA) */
export function autoReply(locale, dept, urgency, escalate) {
  const L = [seg(locale, "您好,感谢来信。", "Hello, thank you for reaching out."), openSeg(locale, dept)];
  if (escalate) {
    L.push(seg(locale,
      "您的问题需要人工坐席跟进,已为您转接人工处理,请留意后续专属回复。",
      "Your case requires a human agent and has been escalated; please watch for a personal follow-up."));
  } else {
    L.push(toneSeg(locale, urgency));
  }
  L.push(seg(locale,
    "如有补充信息,请直接回复本邮件。\n(本邮件由智能客服助手自动发送)",
    "If you have more information, simply reply to this email.\n(This message was sent automatically by the assistant.)"));
  return L.join("\n");
}

/** 人工坐席话术建议稿(复述客户原文 + 按部门/紧急度的跟进话术) */
export function humanScript(locale, dept, urgency, userText) {
  const L = [seg(locale, "【人工话术 · 建议稿】", "[Human script · draft]")];
  L.push(seg(locale, "客户优先级: ", "Customer priority: ") + Math.min(5, Math.max(1, urgency)) + "/5");
  L.push(seg(locale, "客户原文:", "Customer message:") + " " + userText);
  L.push(seg(locale, "建议回复:", "Suggested reply:") + "\n" + humanOpenSeg(locale, dept) + " " +
    seg(locale, "处理完成后我会第一时间与您确认,请放心。", "I will confirm with you as soon as it is resolved."));
  if (urgency >= 4) L.push(seg(locale,
    "※ 该工单优先级较高,建议优先跟进。",
    "* High priority ticket; suggest handling first."));
  return L.join("\n");
}

/** 解析 ticket/multi 三问 → {dept, urgency, escalate};非客服业务返回 null */
export function ticketParts(task, answers) {
  if (task !== "ticket" && task !== "multi") return null;
  const a = answers || {};
  const dept = a.q0?.choice;
  if (!dept) return null;
  const urgency = Math.min(5, Math.max(1, Math.trunc(a.q1?.score ?? 3))); // 对齐 Kotlin toInt() 截断
  const escalate = (a.q2?.noul ?? 0) >= 0.5 || urgency >= 4;
  return { dept, urgency, escalate };
}

/** 决策输出附带的回复模板段(须转人工→人工话术;否则→自动回复正文);非客服业务返回 ""。
 *  对齐 DecisionCore.replyAppendix:task/ticket+multi,locale 默认 zh */
export function replyAppendix(task, answers, text, locale = "zh") {
  const p = ticketParts(task, answers);
  if (!p) return "";
  return p.escalate
    ? "\n----\n【人工回复】\n" + humanScript(locale, p.dept, p.urgency, text)
    : "\n----\n【自动回复】\n" + autoReply(locale, p.dept, p.urgency, p.escalate);
}
