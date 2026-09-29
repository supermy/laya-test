import { Laya } from "@receptron/laya";
import { buildSequence, toInternal } from "./node_modules/@receptron/laya/dist/sequence.js";
const laya = await Laya.load({ modelDir: "./laya-onnx-int8", executionProviders: ["wasm"], sessionOptions: { externalData: [{ path: "model.onnx.data", data: "/sdcard/models/laya-onnx-int8/model.onnx.data" }] } });
const state = { from: "user@acme.com", subject: "Duplicate charge on invoice #4411", body: "Hi, we were billed twice for March. Please refund the duplicate today or we will cancel our plan." };
const questions = {
  department: { type: "choice", instructions: "Which team should handle this ticket?", criteria: { billing: "payments, refunds, invoices", support: "product help and bugs", sales: "new purchases" } },
  urgency: { type: "score", instructions: "How urgent is this ticket?", criteria: ["not urgent", "somewhat urgent", "urgent", "critical"] },
  churn_risk: { type: "noul", instructions: "Is the customer likely to cancel or dispute?" },
};
for (const [qid, q] of Object.entries(questions)) {
  const qi = toInternal(q);
  const { ids, markers } = buildSequence(laya.encode.bind(laya), laya.ids, state, qi, laya.config.max_len, laya.config.head_max_len);
  console.log(`${qid}: seq=${ids.length} opts=${markers.length} qtype=${qi.t}`);
}
await laya.close();
