// 三段计时:prep(tokenize+拼序列) → infer(socket+daemon Run) → post(softmax 等)
import { LayaNative } from "./laya-native.mjs";
const MODEL = process.env.LAYA_MODEL === "en" ? "en" : "multi";
const cfg = MODEL === "en"
  ? { modelDir: "./laya-onnx-int8", modelPath: "/sdcard/models/laya-onnx-int8/model.onnx", socketPath: new URL("./laya-en-t.sock", import.meta.url).pathname,
      extra: { sessionOptions: { externalData: [{ path: "model.onnx.data", data: "/sdcard/models/laya-onnx-int8/model.onnx.data" }] } } }
  : { modelDir: "./laya-multilingual-int8", modelPath: "/sdcard/models/laya-multilingual-int8/laya-multilingual.int8.onnx", socketPath: new URL("./laya-multi-t.sock", import.meta.url).pathname, extra: {} };
const laya = await LayaNative.loadWithDaemon({ ...cfg, threads: 6, env: { ...process.env, LD_LIBRARY_PATH: process.env.LD_LIBRARY_PATH ?? "" } });
const state = { from: "user@acme.com", subject: "Duplicate charge on invoice #4411", body: "Hi, we were billed twice for March. Please refund the duplicate today or we will cancel our plan." };
const QS = {
  department: { type: "choice", instructions: "Which team should handle this ticket?", criteria: { billing: "a payment the user already made is wrong: duplicate charge, invoice error, refund", technical: "the product does not work: bugs, crashes, errors, outage", sales: "the user wants to become a customer: pricing quote, plans, trial, contract before buying" } },
  urgency: { type: "score", instructions: "How urgent is this ticket?", criteria: ["not urgent", "somewhat urgent", "urgent", "critical"] },
  intent: { type: "choice", instructions: "What does the user mainly ask for in this ticket? Base the answer on what the user wants to happen next.", criteria: { refund: "be given money back for a payment that was already made", fix: "have a technical problem repaired so they can keep using the product", info: "receive information, pricing or a general answer" } },
};
for (let i = 0; i < 3; i++) await laya.systemOne(state, QS); // warm
const rows = { prep: [], infer: [], post: [] };
for (let i = 0; i < 10; i++) {
  const r = await laya.systemOne(state, QS);
  rows.prep.push(r._prepMs); rows.infer.push(r._inferMs); rows.post.push(r._postMs);
}
const avg = (a) => (a.reduce((x, y) => x + y, 0) / a.length).toFixed(2);
console.log(`[${MODEL}] 10-run avg: prep=${avg(rows.prep)}ms  infer(socket+Run)=${avg(rows.infer)}ms  post=${avg(rows.post)}ms`);
await laya.shutdown();
process.exit(0);
