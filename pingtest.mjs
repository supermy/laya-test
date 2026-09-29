import { LayaNative } from "./laya-native.mjs";
const t0 = Date.now();
const n = await LayaNative.load({ modelDir: "./laya-onnx-int8", socketPath: new URL("./laya.sock", import.meta.url).pathname });
console.log(`connected in ${Date.now()-t0}ms`);
const r = await n.systemOne({ from: "user@acme.com", subject: "Duplicate charge", body: "billed twice, refund or we cancel" }, {
  department: { type: "choice", instructions: "Which team?", criteria: { billing: "payments", support: "bugs" } },
});
console.log("infer ok:", JSON.stringify(r.answers.department), "in", r._inferMs.toFixed(0), "ms");
n.sock.end();
process.exit(0);
