// Compare int8 (MatMulNBits) vs fp32 Laya, same questions
import { Laya } from "@receptron/laya";

const MODEL_DIR = new URL("./laya-onnx-int8", import.meta.url).pathname;
const EXT_KEY = process.argv[2] ?? "model.onnx.data"; // external data key inside onnx graph

console.log("loading int8 model (606MB)...");
const t0 = Date.now();
const laya = await Laya.load({
  modelDir: MODEL_DIR,
  executionProviders: ["wasm"],
  sessionOptions: { externalData: [{ path: EXT_KEY, data: `${MODEL_DIR}/model.onnx.data` }] },
});
console.log(`loaded in ${((Date.now() - t0) / 1000).toFixed(1)}s`);

const state = {
  from: "user@acme.com",
  subject: "Duplicate charge on invoice #4411",
  body: "Hi, we were billed twice for March. Please refund the duplicate today or we will cancel our plan.",
};
const questions = {
  department: {
    type: "choice",
    instructions: "Which team should handle this ticket?",
    criteria: { billing: "payments, refunds, invoices", support: "product help and bugs", sales: "new purchases" },
  },
  urgency: {
    type: "score",
    instructions: "How urgent is this ticket?",
    criteria: ["not urgent", "somewhat urgent", "urgent", "critical"],
  },
  churn_risk: { type: "noul", instructions: "Is the customer likely to cancel or dispute?" },
};

// warmup
await laya.systemOne(state, questions);

let t = Date.now();
const r1 = await laya.systemOne(state, questions);
const dt1 = Date.now() - t;
console.log("\n--- english ---");
console.log("department:", r1.answers.department.choice, r1.answers.department.probabilities);
console.log("urgency:", r1.answers.urgency.score);
console.log("churn_risk:", r1.answers.churn_risk.noul);
console.log(`inference: ${dt1}ms`);

t = Date.now();
const r2 = await laya.systemOne(
  { subject: "退款未到账", body: "我两周前取消了订阅,到现在还没收到退款,再不处理我就要投诉了。" },
  {
    department: {
      type: "choice",
      instructions: "这条工单应该由哪个团队处理?",
      criteria: { billing: "支付、退款、发票", support: "产品使用和故障", sales: "新购买" },
    },
  },
);
console.log("\n--- 中文 ---");
console.log("department:", r2.answers.department.choice, r2.answers.department.probabilities);
console.log(`inference: ${Date.now() - t}ms`);

// 多轮测均值
const times = [];
for (let i = 0; i < 5; i++) {
  t = Date.now();
  await laya.systemOne(state, questions);
  times.push(Date.now() - t);
}
console.log("\n5-run avg:", (times.reduce((a, b) => a + b) / times.length).toFixed(0), "ms", times.join(","));

await laya.close();
console.log("DONE");
