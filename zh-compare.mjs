import { Laya } from "@receptron/laya";
const CASES = [
  ["中文 退款", { subject: "退款未到账", body: "我两周前取消了订阅,到现在还没收到退款,再不处理我就要投诉了。" }, "billing"],
  ["中文 500故障", { subject: "登录持续报 500", body: "今天早上开始生产环境登录就一直报 500 错误,整个团队完全无法办公,急需修复。" }, "technical"],
  ["中文 询价", { subject: "企业版价格咨询", body: "我们公司大概 150 人,想了解一下企业版的价格和合同方案。" }, "sales"],
  ["中文 how-to", { subject: "怎么导出 PDF", body: "免费版能导出 PDF 吗?设置在哪里?" }, "other"],
  ["DE 退款", { subject: "Rückerstattung fehlt", body: "Ich habe mein Abo vor zwei Wochen gekündigt, aber die Rückerstattung fehlt immer noch. Bitte überweisen Sie das Geld zurück." }, "billing"],
  ["EN 询价", { subject: "Pricing for 200 seats", body: "Could you share the enterprise pricing for 200 seats and the contract terms?" }, "sales"],
];
const QS = {
  department: { type: "choice", instructions: "Which team should handle this ticket?",
    criteria: { billing: "a payment the user already made is wrong: duplicate charge, invoice error, refund",
                technical: "the product does not work: bugs, crashes, errors, outage",
                sales: "the user wants to become a customer: pricing quote, plans, trial, contract before buying",
                other: "general questions or how-to, not covered above" } },
  urgency: { type: "score", instructions: "How urgent is this ticket?", criteria: ["not urgent", "somewhat urgent", "urgent", "critical"] },
  intent: { type: "choice", instructions: "What does the user mainly ask for in this ticket? Base the answer on what the user wants to happen next.",
    criteria: { refund: "be given money back for a payment that was already made",
                fix: "have a technical problem repaired so they can keep using the product",
                info: "receive information, pricing or a general answer" } },
};
for (const [label, modelDir] of [["multi-int8", "./laya-multilingual-int8"], ["en-int8", "./laya-onnx-int8"]]) {
  console.log(`\n===== ${label} =====`);
  const laya = await Laya.load({ modelDir, executionProviders: ["wasm"],
    sessionOptions: modelDir.includes("multi") ? {} : { externalData: [{ path: "model.onnx.data", data: "/sdcard/models/laya-onnx-int8/model.onnx.data" }] } });
  let ok = 0;
  for (const [name, state, want] of CASES) {
    try {
      const r = await laya.systemOne(state, QS);
      const dept = r.answers.department.choice, prob = r.answers.department.probabilities[dept].toFixed(2);
      const pass = dept === want; ok += pass;
      console.log(`${pass ? "PASS" : "FAIL"} ${name}: ${dept}(${prob}) urgency=${r.answers.urgency.score.toFixed(2)} intent=${r.answers.intent.choice} [${r._inferMs ?? "?"}ms]`);
    } catch (e) { console.log(`ERR  ${name}: ${e.message.slice(0, 120)}`); }
  }
  console.log(`→ ${ok}/${CASES.length}`);
  await laya.close();
}
