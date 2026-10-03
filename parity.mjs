import { LayaNative } from "./laya-native.mjs";
const E = (m) => process.stderr.write("[parity] " + m + "\n");
const state = `客户编号: T-2026-0901
渠道: 邮件
主题: 发票金额与扣款不一致
正文: 我上个月被扣了两次月费,一共 598 元,但发票只开了 299 元。
而且我申请退款已经两周了还没有到账,请尽快处理,否则我要投诉了。`;
const questions = {
  intent: { type: "choice", instructions: "Which department should handle this ticket?",
    criteria: { billing: "invoice, refund, payment, subscription price",
      technical: "bugs, errors, how-to, integration problems",
      sales: "pricing questions before purchase, quotes",
      other: "anything that does not match the other options" } },
  urgency: { type: "score", instructions: "How urgent is this ticket on a scale from 0 (can wait) to 4 (critical)?",
    criteria: ["can wait a week", "few days", "today", "hours", "critical, act now"] },
  refund_request: { type: "noul", instructions: "Does the customer explicitly ask for money back?",
    criteria: { false: "no refund mentioned", true: "explicit refund request" } },
};
E("loadWithDaemon...");
const client = await LayaNative.loadWithDaemon({
  modelDir: "/sdcard/models/laya-multilingual-int8",
  modelPath: "/sdcard/models/laya-multilingual-int8/laya-multilingual.int8.onnx",
  socketPath: "/data/data/com.termux/files/home/laya-test/laya-multi-gpu-parity.sock",
  threads: 6,
  env: { ...process.env, LD_LIBRARY_PATH: process.env.LD_LIBRARY_PATH },
});
E("connected, infer...");
client.config.batch1 = true; // multi int8 图只支持 batch=1
const res = await client.systemOne(state, questions);
E("done");
console.log(JSON.stringify(res.answers));
process.exit(0);
