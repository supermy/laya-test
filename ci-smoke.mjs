// CI 冒烟:真实模型 + 原生 runner daemon,验证 C/协议/客户端全链路
// 前置:ORT_LIB(lib 目录)、ci-model/(laya.onnx + laya_config.json + tokenizer/)
import { LayaNative } from "./laya-native.mjs";

const CASES = [
  ["中文退款→billing", { subject: "退款未到账", body: "我两周前取消了订阅,到现在还没收到退款,再不处理我就要投诉了。" }, "billing", "refund"],
  ["EN宕机→technical", { from: "ops@corp.io", subject: "Production outage", body: "Our production integration is completely down since 09:00 UTC. All API calls return 503. Critical blocker." }, "technical", "fix"],
  ["中文how-to→other", { subject: "怎么导出 PDF", body: "免费版能导出 PDF 吗?设置在哪里?" }, "other", "info"],
];

const laya = await LayaNative.loadWithDaemon({
  modelDir: "./ci-model",
  modelPath: "./ci-model/laya.onnx",
  socketPath: "./laya-ci.sock",
  threads: 4,
  env: { ...process.env, LD_LIBRARY_PATH: process.env.ORT_LIB ?? "" },
});

// 预热
await laya.systemOne(CASES[0][1], QUESTIONS());

let pass = 0;
const lats = [];
for (const [name, state, wantDept, wantIntent] of CASES) {
  const r = await laya.systemOne(state, QUESTIONS());
  lats.push(r._inferMs);
  const dept = r.answers.department.choice;
  const intent = r.answers.intent.choice;
  const ok = dept === wantDept && intent === wantIntent;
  if (ok) pass++;
  console.log(`${ok ? "PASS" : "FAIL"} ${name}: dept=${dept} intent=${intent} [${r._inferMs.toFixed(0)}ms]`);
  if (!ok) console.log(`  got: ${JSON.stringify(r.answers)}`);
}
console.log(`smoke: ${pass}/${CASES.length} passed, avg ${Math.round(lats.reduce((a, b) => a + b) / lats.length)}ms`);
await laya.shutdown();
process.exit(pass === CASES.length ? 0 : 1);

function QUESTIONS() {
  return {
    department: {
      type: "choice",
      instructions: "Which team should handle this ticket?",
      criteria: {
        billing: "a payment the user already made is wrong: duplicate charge, invoice error, refund",
        technical: "the product does not work: bugs, crashes, errors, outage",
        sales: "the user wants to become a customer: pricing quote, plans, trial, contract before buying",
        other: "general questions or how-to, not covered above",
      },
    },
    urgency: {
      type: "score",
      instructions: "How urgent is this ticket?",
      criteria: ["not urgent", "somewhat urgent", "urgent", "critical"],
    },
    intent: {
      type: "choice",
      instructions: "What does the user mainly ask for in this ticket? Base the answer on what the user wants to happen next.",
      criteria: {
        refund: "be given money back for a payment that was already made",
        fix: "have a technical problem repaired so they can keep using the product",
        info: "receive information, pricing or a general answer",
      },
    },
  };
}
