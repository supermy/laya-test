#!/usr/bin/env node
// 真实历史工单全量回归:Tobi-Bueck/customer-support-tickets(en/de, 28.6k)
// 分层抽样 → laya serve → 指标:部门混淆矩阵/准确率、urgency vs priority、intent vs 词汇退款真值
// 用法: node validate-historical.mjs [样本数/队列,默认 12] [并发,默认 3]
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = 8787, BASE = `http://127.0.0.1:${PORT}`;
const CONC = Number(process.argv[2] ?? 2);

// queue → 我们的四部门
const QUEUE_MAP = {
  "Technical Support": "technical",
  "IT Support": "technical",
  "Service Outages and Maintenance": "technical",
  "Billing and Payments": "billing",
  "Sales and Pre-Sales": "sales",
  "Product Support": "other",
  "Customer Service": "other",
  "Returns and Exchanges": "other",
  "General Inquiry": "other",
  "Human Resources": "other",
};

const QUESTIONS = {
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

// ---- 1. 读取样本(Python csv 模块预生成的分层抽样) ----
function loadSample() {
  const rows = fs.readFileSync(path.join(HERE, process.env.SAMPLE_FILE ?? "sample.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
  return { sample: rows, total: 28587, groups: 20 };
}

// ---- 2. HTTP ----
async function ensureServe() {
  for (let i = 0; i < 5; i++) {
    try { const r = await fetch(`${BASE}/health`, { signal: AbortSignal.timeout(1500) }); if (r.ok) return; } catch {}
    await new Promise((r) => setTimeout(r, 1000));
  }
  console.log("拉起 laya serve(冷启动 15-25s)...");
  const child = spawn("laya", ["serve", "--port", String(PORT)], { stdio: ["ignore", "ignore", "ignore"], detached: true });
  child.unref();
  const t0 = Date.now();
  for (;;) {
    try { const r = await fetch(`${BASE}/health`, { signal: AbortSignal.timeout(1500) }); if (r.ok) break; } catch {}
    if (Date.now() - t0 > 90000) throw new Error("serve 启动超时");
    await new Promise((r) => setTimeout(r, 1500));
  }
}

const REV_CRIT = Object.fromEntries(Object.entries(QUESTIONS.department.criteria).reverse());
async function inferOne(t) {
  const state = { subject: t.subject.slice(0, 300), body: t.body.slice(0, 1200) }; // 截断到 ~300 token,控延迟(模型本就 512 截断)
  const call = (questions) => fetch(`${BASE}/system-one`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ state, questions }), signal: AbortSignal.timeout(60000),
  }).then(async (r) => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); });
  const [a, b] = await Promise.all([call(QUESTIONS), call({ ...QUESTIONS, department: { ...QUESTIONS.department, criteria: REV_CRIT } })]);
  const probs = {};
  for (const k of Object.keys(QUESTIONS.department.criteria)) probs[k] = (a.answers.department.probabilities[k] + b.answers.department.probabilities[k]) / 2;
  return {
    department: Object.entries(probs).sort((x, y) => y[1] - x[1])[0][0],
    urgency: a.answers.urgency.score,
    intent: a.answers.intent.choice,
    latencyMs: a.latencyMs,
  };
}

// ---- 3. 主流程 ----
const { sample, total, groups } = loadSample();
console.log(`数据集 28,587 张(有效映射 ${total}),分层抽样 ${groups} 组 × 12 = ${sample.length} 张`);
await ensureServe();
console.log("serve 就绪,开始回归...\n");

const results = [];
let cursor = 0, done = 0;
async function worker() {
  while (cursor < sample.length) {
    const t = sample[cursor++];
    try {
      const r = await inferOne(t);
      results.push({ ...t, ...r });
    } catch (e) {
      results.push({ ...t, error: String(e.message ?? e) });
    }
    done++;
    if (done % 20 === 0) console.log(`  进度 ${done}/${sample.length}`);
  }
}
const t0 = Date.now();
await Promise.all(Array.from({ length: CONC }, worker));
console.log(`\n完成 ${results.length} 张,用时 ${((Date.now() - t0) / 1000).toFixed(0)}s\n`);

// ---- 4. 指标 ----
const ok = results.filter((r) => !r.error);
const errors = results.length - ok.length;
const deptOk = ok.filter((r) => r.department === QUEUE_MAP[r.queue]);
console.log(`== 部门分流(四部门)==`);
console.log(`准确率: ${deptOk.length}/${ok.length} = ${(deptOk.length / ok.length * 100).toFixed(1)}%`);

const conf = {};
for (const r of ok) {
  const gt = QUEUE_MAP[r.queue];
  conf[`${gt}→${r.department}`] = (conf[`${gt}→${r.department}`] ?? 0) + 1;
}
const classes = ["billing", "technical", "sales", "other"];
console.log("\nconfusion (行=真值,列=预测)  " + classes.join("  "));
for (const gt of classes) {
  const row = classes.map((p) => String(conf[`${gt}→${p}`] ?? 0).padStart(6));
  const recall = (conf[`${gt}→${gt}`] ?? 0) / Math.max(1, ok.filter((r) => QUEUE_MAP[r.queue] === gt).length);
  console.log(`${gt.padEnd(9)} ${row.join(" ")}   recall=${(recall * 100).toFixed(0)}%`);
}
for (const p of classes) {
  const tp = conf[`${p}→${p}`] ?? 0;
  const fp = ok.filter((r) => r.department === p && QUEUE_MAP[r.queue] !== p).length;
  console.log(`precision ${p.padEnd(9)} ${(tp / Math.max(1, tp + fp) * 100).toFixed(0)}%`);
}

console.log(`\n== urgency vs priority ==`);
for (const pr of ["low", "medium", "high"]) {
  const list = ok.filter((r) => r.priority === pr && r.urgency !== undefined);
  if (!list.length) continue;
  const avg = list.reduce((s, r) => s + r.urgency, 0) / list.length;
  console.log(`priority=${pr.padEnd(6)} n=${String(list.length).padEnd(4)} avg_urgency=${avg.toFixed(2)}`);
}

console.log(`\n== intent(退款判别,词汇真值)==`);
const refundRe = /\b(refund|money back|Rückerstattung|erstatten|Erstattung)\b/i;
const pos = ok.filter((r) => refundRe.test(r.subject + " " + r.body));
const posOk = pos.filter((r) => r.intent === "refund");
console.log(`含退款词汇: ${pos.length} 张 → 判为 refund ${posOk.length}(${pos.length ? (posOk.length / pos.length * 100).toFixed(0) : 100}%)`);
const techNoRefund = ok.filter((r) => QUEUE_MAP[r.queue] === "technical" && !refundRe.test(r.subject + " " + r.body));
const fixOk = techNoRefund.filter((r) => r.intent === "fix");
console.log(`技术工单无退款词: ${techNoRefund.length} 张 → 判为 fix ${fixOk.length}(${techNoRefund.length ? (fixOk.length / techNoRefund.length * 100).toFixed(0) : 100}%)`);

const lats = ok.map((r) => r.latencyMs).sort((a, b) => a - b);
console.log(`\n== 延迟(单次 systemOne,不含去偏第二路)==`);
console.log(`p50=${lats[Math.floor(lats.length / 2)]}ms  p90=${lats[Math.floor(lats.length * 0.9)]}ms  max=${lats.at(-1)}ms`);
if (errors) {
  console.log(`\n⚠ ${errors} 张请求失败:`);
  for (const msg of [...new Set(results.filter((r) => r.error).map((r) => r.error))].slice(0, 3)) console.log(`   ${msg}`);
}
const acc = deptOk.length / ok.length;
console.log(`\n结论: 部门准确率 ${(acc * 100).toFixed(1)}%(阈值 70% ${acc >= 0.7 ? "通过 ✓" : "未达标 ✗"})`);
process.exitCode = acc >= 0.7 && !errors ? 0 : 1;
