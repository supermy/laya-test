// E2E test: LiteRT NPU (AOT dispatch) runner in Termux domain, no adb.
// Usage: LAYA_* env vars select model; node e2e-litert-npu.mjs [rounds] [--stop]
import { loadLitert, stopLitert } from "./laya-litert.mjs";

const state = `客户编号: T-2026-0901
渠道: 邮件
主题: 发票金额与扣款不一致
正文: 我上个月被扣了两次月费,一共 598 元,但发票只开了 299 元。
而且我申请退款已经两周了还没有到账,请尽快处理,否则我要投诉了。`;

const questions = {
  intent: {
    type: "choice",
    instructions: "Which department should handle this ticket?",
    criteria: {
      billing: "invoice, refund, payment, subscription price",
      technical: "bugs, errors, how-to, integration problems",
      sales: "pricing questions before purchase, quotes",
      other: "anything that does not match the other options",
    },
  },
  urgency: {
    type: "score",
    instructions: "How urgent is this ticket on a scale from 0 (can wait) to 4 (critical)?",
    criteria: ["can wait a week", "few days", "today", "hours", "critical, act now"],
  },
  refund_request: {
    type: "noul",
    instructions: "Does the customer explicitly ask for money back?",
    criteria: { false: "no refund mentioned", true: "explicit refund request" },
  },
};

const modelDir = process.env.LAYA_MODEL_DIR || "/sdcard/models/laya-litert-ticket";
const rounds = Number(process.argv[2] ?? 3);
const client = await loadLitert({ modelDir });
console.log("connected to litert runner (npu)");

const t0 = performance.now();
let first = null;
for (let r = 0; r < rounds; r++) {
  const a = performance.now();
  const res = await client.systemOne(state, questions);
  const dt = performance.now() - a;
  if (r === 0) first = res;
  console.log(`round ${r}: ${dt.toFixed(0)} ms (infer ${res._inferMs?.toFixed(0)} ms)`);
}
console.log(`avg over ${rounds}: ${((performance.now() - t0) / rounds).toFixed(0)} ms`);

console.log(JSON.stringify(first.answers, null, 2));
if (process.argv.includes("--stop")) await stopLitert();
process.exit(0);
