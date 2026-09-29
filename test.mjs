// Test Laya (System-1 decision model) on Termux via ort-web WASM shim
import { Laya } from "@receptron/laya";

const MODEL_DIR = "/sdcard/models/laya-onnx";

console.log("loading model (fp32, ~1.7GB)...");
const t0 = Date.now();
const laya = await Laya.load({
  modelDir: MODEL_DIR,
  executionProviders: ["wasm"],
  sessionOptions: {
    externalData: [{ path: "laya.onnx.data", data: `${MODEL_DIR}/laya.onnx.data` }],
  },
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
    criteria: {
      billing: "payments, refunds, invoices",
      support: "product help and bugs",
      sales: "new purchases",
    },
  },
  urgency: {
    type: "score",
    instructions: "How urgent is this ticket?",
    criteria: ["not urgent", "somewhat urgent", "urgent", "critical"],
  },
  churn_risk: { type: "noul", instructions: "Is the customer likely to cancel or dispute?" },
};

const t1 = Date.now();
const result = await laya.systemOne(state, questions);
const dt = Date.now() - t1;

console.log("\n--- result ---");
console.log(JSON.stringify(result.answers, null, 2));
console.log("routing:", result.routing);
console.log("usage:", result.usage);
console.log(`inference: ${dt}ms (cold)`);

// warm run
const t2 = Date.now();
await laya.systemOne(state, questions);
console.log(`inference: ${Date.now() - t2}ms (warm)`);

// 中文测试
const zh = await laya.systemOne(
  { subject: "退款未到账", body: "我两周前取消了订阅,到现在还没收到退款,再不处理我就要投诉了。" },
  {
    department: {
      type: "choice",
      instructions: "这条工单应该由哪个团队处理?",
      criteria: {
        billing: "支付、退款、发票",
        support: "产品使用和故障",
        sales: "新购买",
      },
    },
  },
);
console.log("\n--- 中文 ---");
console.log("department:", zh.answers.department.choice, zh.answers.department.probabilities);

await laya.close();
console.log("\nDONE");
