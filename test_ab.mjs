import { LayaNative } from "./laya-native.mjs";

const [port, nStr] = process.argv.slice(2);
const n = parseInt(nStr || "20", 10);
const modelDir = "/sdcard/models/laya-cancer-int8";
const cfg = JSON.parse((await import("fs")).readFileSync(modelDir + "/laya_config.json", "utf8"));
const questions = Object.fromEntries(cfg.question_defs.map((q) => [q.qid, { type: q.type, instructions: q.instructions, criteria: q.criteria }]));
const c = await LayaNative.load({ modelDir, socketPath: parseInt(port, 10) });
const state = { text: "医生您好,我母亲今年58岁,体检发现CA125指标升高至85U/mL,腹部B超提示盆腔少量积液,请问需要进一步做哪些检查排除卵巢病变?" };
// warmup
for (let i = 0; i < 3; i++) await c.systemOne(state, questions);
const times = [];
let last;
for (let i = 0; i < n; i++) {
  const r = await c.systemOne(state, questions);
  times.push(r._inferMs);
  last = r.answers;
}
times.sort((a, b) => a - b);
const avg = times.reduce((s, x) => s + x, 0) / times.length;
console.log("avg", avg.toFixed(1), "ms | p50", times[Math.floor(times.length / 2)].toFixed(1), "| min", times[0].toFixed(1), "| max", times[times.length - 1].toFixed(1));
console.log("answers q0:", last.q0.choice, "q1:", last.q1.score ?? JSON.stringify(last.q1).slice(0, 60));
process.exit(0);
