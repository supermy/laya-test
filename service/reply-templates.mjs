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

// ---- 其他业务模板(ugc 内容审核 / agent 工作流路由 / risk 风控前置 / 通用兜底) ----

/** 审核判定开头段:正常放行 / 分类处置 */
function ugcOpenSeg(locale, verdict) {
  if (verdict === "正常") return seg(locale,
    "该内容已通过机器审核,未发现违规风险,可正常展示。",
    "The content has passed automated review; no violations detected and it can be published.");
  if (verdict === "广告导流") return seg(locale,
    "该内容涉嫌广告导流,已按社区规则限流并挂起展示。",
    "The content is suspected of ad diversion and has been throttled and held per community rules.");
  return seg(locale, // 辱骂攻击/色情低俗/诈骗引流 + 未知判定
    "该内容涉嫌「" + verdict + "」违规,已自动拦截下架。",
    "The content is suspected of violating rules (\"" + verdict + "\") and has been automatically removed.");
}

/** ugc 自动回复:q0 审核判定, q1 违规严重度1-5, q2 人工复核 */
export function ugcReply(locale, verdict, severity, escalate) {
  const L = [seg(locale, "您好,该内容已完成机器审核。", "Hello, the content has completed automated review."), ugcOpenSeg(locale, verdict)];
  if (escalate) {
    L.push(seg(locale,
      "该内容需要人工复核,已转交审核专员处理,请留意复核结论。",
      "This content requires human review and has been escalated to a specialist; please await the review conclusion."));
  } else if (verdict !== "正常") {
    L.push(severity >= 3
      ? seg(locale, "违规严重度为 " + severity + "/5,已同步留存证据并纳入风控名单。",
            "Violation severity is " + severity + "/5; evidence has been preserved and added to the risk list.")
      : seg(locale, "违规严重度为 " + severity + "/5,将在 24 小时内自动完成复核归档。",
            "Violation severity is " + severity + "/5; automated re-check will be archived within 24 hours."));
  }
  L.push(seg(locale, "(本结果由智能内容审核助手自动生成)",
    "(This result was generated automatically by the content moderation assistant.)"));
  return L.join("\n");
}

/** ugc 人工复核建议稿 */
export function ugcScript(locale, verdict, severity, userText) {
  const L = [seg(locale, "【人工复核 · 建议稿】", "[Human review · draft]")];
  L.push(seg(locale, "违规严重度: ", "Violation severity: ") + severity + "/5");
  L.push(seg(locale, "审核判定: ", "Moderation verdict: ") + verdict);
  L.push(seg(locale, "内容原文:", "Content:") + " " + userText);
  L.push(seg(locale, "复核建议: 请核对判定与严重度是否准确,并补充处置依据。",
    "Review tips: verify the verdict and severity, and document the action taken."));
  return L.join("\n");
}

/** agent 路由结尾说明段:复杂度1-5 */
function agentComplexSeg(locale, complexity) {
  const c = Math.min(5, Math.max(1, complexity));
  const NAMES = ["", "一句话", "简单", "中等", "复杂", "极复杂"];
  return seg(locale,
    "任务复杂度为 " + c + "/5(" + NAMES[c] + "),工作流将按步骤执行。",
    "Task complexity is " + c + "/5; the workflow will execute step by step.");
}

/** agent 自动回复:q0 路由工作流, q1 复杂度1-5, q2 任务拆解 */
export function agentReply(locale, route, complexity, escalate) {
  const L = [seg(locale, "您好,您的请求已完成智能路由。",
    "Hello, your request has been routed by the intelligent dispatcher.")];
  if (escalate) {
    L.push(seg(locale,
      "该任务涉及多实体或多约束,已转交人工坐席拆解处理,请留意后续进展。",
      "The task involves multiple entities or constraints and has been escalated to a human agent for decomposition."));
  } else {
    L.push(seg(locale, "将交由「" + route + "」工作流处理。",
      "It will be handled by the \"" + route + "\" workflow."));
    L.push(agentComplexSeg(locale, complexity));
  }
  L.push(seg(locale, "(本路由结果由智能 Agent 调度助手自动生成)",
    "(This routing result was generated automatically by the agent dispatcher.)"));
  return L.join("\n");
}

/** agent 人工拆解建议稿 */
export function agentScript(locale, route, complexity, userText) {
  const L = [seg(locale, "【任务拆解 · 建议稿】", "[Task decomposition · draft]")];
  L.push(seg(locale, "任务复杂度: ", "Task complexity: ") + complexity + "/5");
  L.push(seg(locale, "原始路由: ", "Initial routing: ") + route);
  L.push(seg(locale, "任务原文:", "Task message:") + " " + userText);
  L.push(seg(locale, "拆解建议: 先识别实体与约束,再拆分步骤并逐条确认执行顺序。",
    "Tips: identify entities and constraints first, then split into steps and confirm the order."));
  return L.join("\n");
}

/** risk 处置行动段:强度1-5 */
function riskActionSeg(locale, strength, escalate) {
  if (escalate || strength >= 4) return seg(locale,
    "已触发强风控策略,本次请求将被拦截并转人工复核。",
    "Strong risk-control policy triggered; the request is blocked pending human review.");
  if (strength === 3) return seg(locale,
    "已触发加强验证流程,请配合完成二次核验。",
    "Enhanced verification triggered; please complete the secondary check.");
  return seg(locale, "未发现显著风险,按常规流程放行。",
    "No significant risk detected; proceeding with the standard flow.");
}

/** risk 自动回复:q0 风险信号强度1-5, q1 主要风险类型, q2 人工复核 */
export function riskReply(locale, strength, riskType, escalate) {
  const L = [seg(locale, "您好,该请求已完成风控前置研判。",
    "Hello, this request has completed pre-screening risk control.")];
  L.push(seg(locale, "风险信号强度 " + strength + "/5,主要风险类型:「" + riskType + "」。",
    "Risk signal strength is " + strength + "/5; main risk type: \"" + riskType + "\"."));
  L.push(riskActionSeg(locale, strength, escalate));
  L.push(seg(locale, "(本结果由智能风控前置助手自动生成)",
    "(This result was generated automatically by the risk-control assistant.)"));
  return L.join("\n");
}

/** risk 人工复核建议稿 */
export function riskScript(locale, strength, riskType, userText) {
  const L = [seg(locale, "【风控复核 · 建议稿】", "[Risk review · draft]")];
  L.push(seg(locale, "风险信号强度: ", "Risk signal strength: ") + strength + "/5");
  L.push(seg(locale, "主要风险类型: ", "Main risk type: ") + riskType);
  L.push(seg(locale, "请求原文:", "Request message:") + " " + userText);
  L.push(seg(locale, "复核建议: 核实风险类型与强度,确认拦截/放行决策并留痕。",
    "Tips: verify risk type and strength, confirm the block/allow decision and keep an audit trail."));
  return L.join("\n");
}

/** 通用兜底(news/stock/未知业务):确定性优先级 choice > score > noul 取结论 */
function genericParts(answers) {
  const a = answers || {};
  for (const pass of ["choice", "score", "noul"]) {
    for (const k of Object.keys(a)) {
      const x = a[k];
      if (!x || !x.type || x.type !== pass) continue;
      if (pass === "choice") return { kind: "choice", key: k, value: String(x.choice ?? "?"), conf: x.confidence };
      if (pass === "score") return { kind: "score", key: k, value: Math.min(5, Math.max(1, Math.trunc(x.score ?? 3))) };
      if (pass === "noul") return { kind: "noul", key: k, value: x.noul ?? 0 };
    }
  }
  return null;
}

/** 通用自动回复 */
export function genericReply(locale, task, answers, escalate) {
  const p = genericParts(answers);
  const L = [seg(locale, "您好,「" + task + "」智能研判已完成。",
    "Hello, the \"" + task + "\" decision has been completed.")];
  if (!p) L.push(seg(locale, "模型未产出有效结论,建议人工跟进。",
    "The model produced no valid conclusion; manual follow-up is suggested."));
  else if (p.kind === "choice") L.push(seg(locale, "研判结论:「" + p.value + "」。",
    "Conclusion: \"" + p.value + "\"."));
  else if (p.kind === "score") L.push(seg(locale, "研判评分:" + p.value + "/5。",
    "Decision score: " + p.value + "/5."));
  else L.push(seg(locale, "研判信号:" + (p.value >= 0.5 ? "需人工" : "无异常") + "(" + Number(p.value).toFixed(2) + ")。",
    "Signal: " + (p.value >= 0.5 ? "manual review needed" : "normal") + " (" + Number(p.value).toFixed(2) + ")."));
  L.push(escalate
    ? seg(locale, "该结果需要人工确认,已转交专人跟进处理。",
          "This result requires human confirmation and has been escalated for follow-up.")
    : seg(locale, "结论已按策略自动执行。",
          "The conclusion has been applied automatically per policy."));
  L.push(seg(locale, "(本结果由智能决策助手自动生成)",
    "(This result was generated automatically by the decision assistant.)"));
  return L.join("\n");
}

/** 通用人工跟进建议稿 */
export function genericScript(locale, task, answers, userText) {
  const L = [seg(locale, "【人工跟进 · 建议稿】", "[Manual follow-up · draft]")];
  const p = genericParts(answers);
  if (p?.kind === "choice") L.push(seg(locale, "研判结论: ", "Conclusion: ") + p.value);
  if (p?.kind === "score") L.push(seg(locale, "研判评分: ", "Score: ") + p.value + "/5");
  if (p?.kind === "noul") L.push(seg(locale, "研判信号: ", "Signal: ") + Number(p.value).toFixed(2));
  L.push(seg(locale, "原始输入:", "Original input:") + " " + userText);
  L.push(seg(locale, "跟进建议: 核对模型结论与实际业务状态,处理完成后记录结论与依据。",
    "Tips: check the model conclusion against the actual state, then record the outcome and rationale."));
  return L.join("\n");
}

/** 决策输出附带的回复模板段(须转人工→人工话术;否则→自动回复正文)。
 *  ticket/multi 走客服三问;ugc/agent/risk 走业务专属模板;其余走通用兜底。locale 默认 zh */
export function replyAppendix(task, answers, text, locale = "zh") {
  const a = answers || {};
  const anyEscalate = Object.values(a).some((x) => x?.type === "noul" && (x.noul ?? 0) >= 0.5);
  switch (task) {
    case "ticket":
    case "multi": {
      const p = ticketParts(task, a);
      if (!p) return "";
      return p.escalate
        ? "\n----\n【人工回复】\n" + humanScript(locale, p.dept, p.urgency, text)
        : "\n----\n【自动回复】\n" + autoReply(locale, p.dept, p.urgency, p.escalate);
    }
    case "ugc": {
      const verdict = String(a.q0?.choice ?? "正常");
      const severity = Math.min(5, Math.max(1, Math.trunc(a.q1?.score ?? 1)));
      const escalate = anyEscalate || severity >= 4;
      return escalate
        ? "\n----\n【人工复核】\n" + ugcScript(locale, verdict, severity, text)
        : "\n----\n【自动回复】\n" + ugcReply(locale, verdict, severity, escalate);
    }
    case "agent": {
      const route = String(a.q0?.choice ?? "直接回答");
      const complexity = Math.min(5, Math.max(1, Math.trunc(a.q1?.score ?? 1)));
      const escalate = anyEscalate || route === "转人工";
      return escalate
        ? "\n----\n【人工跟进】\n" + agentScript(locale, route, complexity, text)
        : "\n----\n【自动回复】\n" + agentReply(locale, route, complexity, escalate);
    }
    case "risk": {
      const strength = Math.min(5, Math.max(1, Math.trunc(a.q0?.score ?? 1)));
      const riskType = String(a.q1?.choice ?? "无风险信号");
      const escalate = anyEscalate || strength >= 4;
      return escalate
        ? "\n----\n【人工复核】\n" + riskScript(locale, strength, riskType, text)
        : "\n----\n【自动回复】\n" + riskReply(locale, strength, riskType, escalate);
    }
    default:
      return anyEscalate
        ? "\n----\n【人工跟进】\n" + genericScript(locale, task, a, text)
        : "\n----\n【自动回复】\n" + genericReply(locale, task, a, anyEscalate);
  }
}
