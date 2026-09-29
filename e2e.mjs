// E2E verify: native daemon vs wasm — output equality + latency
import { LayaNative } from "./laya-native.mjs";

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
const zhState = { subject: "退款未到账", body: "我两周前取消了订阅,到现在还没收到退款,再不处理我就要投诉了。" };
const zhQuestions = {
  department: {
    type: "choice",
    instructions: "这条工单应该由哪个团队处理?",
    criteria: { billing: "支付、退款、发票", support: "产品使用和故障", sales: "新购买" },
  },
};

console.log("starting native daemon + loading model...");
const t0 = Date.now();
const native = await LayaNative.loadWithDaemon({
  modelDir: new URL("./laya-onnx-int8", import.meta.url).pathname,
  modelPath: "/sdcard/models/laya-onnx-int8/model.onnx",
  socketPath: new URL("./laya.sock", import.meta.url).pathname,
  threads: 6,
});
console.log(`daemon ready in ${((Date.now() - t0) / 1000).toFixed(1)}s`);

// warmup
await native.systemOne(state, questions);

const t1 = Date.now();
const r1 = await native.systemOne(state, questions);
console.log(`\n[infer ${r1._inferMs.toFixed(0)}ms]`);
console.log("department:", JSON.stringify(r1.answers.department.choice), r1.answers.department.probabilities);
console.log("urgency:", r1.answers.urgency.score);
console.log("churn_risk:", r1.answers.churn_risk.noul);

const r2 = await native.systemOne(zhState, zhQuestions);
console.log(`[infer ${r2._inferMs.toFixed(0)}ms] 中文 department:`, JSON.stringify(r2.answers.department.choice), r2.answers.department.probabilities);

// latency stats
const times = [];
for (let i = 0; i < 10; i++) {
  const r = await native.systemOne(state, questions);
  times.push(r._inferMs);
}
const avg = times.reduce((a, b) => a + b) / times.length;
console.log(`\nnative 10-run avg: ${avg.toFixed(0)}ms (min ${Math.min(...times).toFixed(0)}, max ${Math.max(...times).toFixed(0)})`);

// compare vs wasm (fp32 reference + int8)
console.log("\nloading wasm int8 for output comparison...");
const { Laya } = await import("@receptron/laya");
const wasm = await Laya.load({
  modelDir: new URL("./laya-onnx-int8", import.meta.url).pathname,
  executionProviders: ["wasm"],
  sessionOptions: { externalData: [{ path: "model.onnx.data", data: "/sdcard/models/laya-onnx-int8/model.onnx.data" }] },
});
const w1 = await wasm.systemOne(state, questions);
let ok = true;
for (const qid of Object.keys(questions)) {
  const a = JSON.stringify(r1.answers[qid]);
  const b = JSON.stringify(w1.answers[qid]);
  const match = a === b;
  ok = ok && match;
  console.log(`${qid}: ${match ? "MATCH" : "DIFF"}\n  native: ${a}\n  wasm:   ${b}`);
}
console.log(ok ? "\n✓ native == wasm (int8) outputs identical" : "\n✗ OUTPUTS DIFFER");
await wasm.close();
await native.shutdown();
console.log("DONE");
