#!/usr/bin/env node
// 客服实时数据接口决策测试:本地 aedes broker + MQTT 网关(cs 主题)in-process,
// 从 tickets.csv 抽真实客服工单发布到 laya/cs/req,收集 laya/cs/resp 决策结果,
// 与 CSV ground truth(queue 分诊 / priority 紧急度)对照汇总。
//
// 用法: node service/test-cs-mqtt.mjs [--n 20] [--lang en] [--port 18830] [--seed N] [--stub] [--api URL] [--locale zh]
//   --n      抽样工单数(默认 20)
//   --lang   语言过滤 en|de|any(默认 en)
//   --stub   不调真实模型,用确定性假决策(仅测链路)
//   --api    决策后端 URL:默认 http://127.0.0.1:8789(Termux service /task/:id);
//            传 APK 决策 API(如 http://127.0.0.1:8790)则走 POST /decide {"task","text"}
//   --locale 回复模板语言 zh|en(默认 zh)
//   真实决策需要后端已启动(service/server.mjs 或 layatest app 网关页)
//
// MQTT 消息格式:
//   发布 laya/cs/req  {"id","subject","body"} 或 {"id","text"}(task 可选,默认 ticket)
//   订阅 laya/cs/resp {"id","task","answers","latencyMs","reply"}
import { Aedes } from "aedes";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { replyAppendix } from "./reply-templates.mjs";

const require = createRequire(import.meta.url);
const mqtt = require("mqtt");
const HERE = path.dirname(new URL(import.meta.url).pathname);

// ---------- 参数 ----------
const args = process.argv.slice(2);
const opt = (k, d) => {
  const i = args.indexOf("--" + k);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith("--") ? args[i + 1] : d;
};
const N = parseInt(opt("n", "20"), 10);
const LANG = opt("lang", "en");
const PORT = parseInt(opt("port", "18830"), 10);
const SEED = opt("seed", null);
const STUB = args.includes("--stub");
const TASK = opt("task", "ticket");
const API = opt("api", "http://127.0.0.1:8789").replace(/\/$/, "");
const IS_APK = /:8790/.test(API) || args.includes("--apk");
const LOCALE = opt("locale", "zh");
const URL_ = `mqtt://127.0.0.1:${PORT}`;

const log = (...a) => console.log("[cs-test]", ...a);

// ---------- CSV(RFC4180 内联解析) ----------
function csvRows(txt) {
  const out = []; let f = [], cur = "", inq = false;
  for (let i = 0; i < txt.length; i++) {
    const c = txt[i];
    if (inq) { if (c === '"') { if (txt[i + 1] === '"') { cur += '"'; i++; } else inq = false; } else cur += c; }
    else if (c === '"') inq = true;
    else if (c === ",") { f.push(cur); cur = ""; }
    else if (c === "\n") { f.push(cur); out.push(f); f = []; cur = ""; }
    else if (c !== "\r") cur += c;
  }
  if (cur || f.length) { f.push(cur); out.push(f); }
  return out;
}
function loadTickets() {
  const csvPath = path.join(HERE, "..", "tickets.csv");
  const rs = csvRows(fs.readFileSync(csvPath, "utf8"));
  const hdr = rs[0];
  const idx = Object.fromEntries(["subject", "body", "queue", "priority", "language"].map((k) => [k, hdr.indexOf(k)]));
  return rs.slice(1).filter((r) => r.length > idx.body && r[idx.body].trim())
    .map((r) => ({ subject: r[idx.subject], body: r[idx.body], queue: r[idx.queue], priority: r[idx.priority], language: r[idx.language] }));
}

// queue(英文)→ 模型分诊 criteria(中文)对照
const QUEUE_MAP = {
  "Technical Support": "技术支持", "Product Support": "技术支持", "IT Support": "技术支持",
  "Billing and Payments": "账单计费", "Sales and Pre-Sales": "销售咨询",
  "Returns and Exchanges": "退换货", "Service Outages and Maintenance": "故障维护",
  "Customer Service": "其他", "Human Resources": "其他", "General Inquiry": "其他",
};
const PRIORITY_SCORE = { high: 4, medium: 3, low: 2 };

// ---------- 抽样 ----------
function sample(tickets, n, seed) {
  let s = seed == null ? Date.now() : parseInt(seed, 10);
  const rnd = () => (s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  const pool = [...tickets];
  const out = [];
  while (out.length < n && pool.length) out.push(pool.splice(Math.floor(rnd() * pool.length), 1)[0]);
  return out;
}

// ---------- 决策注入 ----------
async function realDecide(task, text) {
  // APK 决策 API(8790):POST /decide {"task","text"} → {answers,latencyMs}
  if (IS_APK) {
    const r = await fetch(`${API}/decide`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ task, text }),
    });
    if (!r.ok) throw new Error(`HTTP ${r.status}: ${(await r.text()).slice(0, 120)}`);
    const j = await r.json();
    return { answers: j.answers, latencyMs: j.latencyMs, reply: j.reply ?? replyAppendix(task, j.answers, text, LOCALE) };
  }
  // Termux service(8789):POST /task/:id {"text"} → {answers,latencyMs,reply}
  const r = await fetch(`${API}/task/${task}`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  if (!r.ok) throw new Error(`HTTP ${r.status}: ${(await r.text()).slice(0, 120)}`);
  const j = await r.json();
  return { answers: j.answers, latencyMs: j.latencyMs, reply: j.reply ?? replyAppendix(task, j.answers, text, LOCALE) };
}
function stubDecide(task, text) {
  let h = 0; for (const ch of text) h = (h * 31 + ch.charCodeAt(0)) & 0xffff;
  const criteria = ["技术支持", "账单计费", "销售咨询", "退换货", "故障维护", "其他"];
  const choice = criteria[h % criteria.length];
  const score = 1 + (h >> 3) % 5;
  return {
    answers: {
      q0: { type: "choice", choice, confidence: 0.5 + (h % 40) / 100 },
      q1: { type: "score", score, legend: { "1": "可忽略", "2": "低", "3": "中", "4": "高", "5": "紧急" } },
      q2: { type: "noul", noul: score >= 4 ? 0.7 : 0.2 },
    },
    latencyMs: 1,
    reply: replyAppendix(task, {
      q0: { choice },
      q1: { score },
      q2: { noul: score >= 4 ? 0.7 : 0.2 },
    }, text, LOCALE),
  };
}

// ---------- 主流程 ----------
const MQ_CFG = path.join(HERE, "config", "mq.json");
const backup = fs.existsSync(MQ_CFG) ? fs.readFileSync(MQ_CFG, "utf8") : null;

const results = [];
let pending = 0;
let aedes, broker, client;

async function main() {
  // 1. 本地 broker
  aedes = await Aedes.createBroker();
  broker = net.createServer(aedes.handle);
  await new Promise((ok) => broker.listen(PORT, "127.0.0.1", ok));
  log(`broker ${URL_}`);

  // 2. 写测试 mq.json + 启动网关(含客服实时主题)
  fs.mkdirSync(path.dirname(MQ_CFG), { recursive: true });
  fs.writeFileSync(MQ_CFG, JSON.stringify({
    enabled: true,
    mqtt: { url: URL_, clientId: "laya-cs-test" },
    topics: { sub: "laya/test/req/+", pub: "laya/test/resp", csSub: "laya/cs/req", csPub: "laya/cs/resp", csTask: TASK },
  }, null, 2));
  const gw = await import("./gateways.mjs");
  gw.setDecideFn(STUB ? stubDecide : realDecide);
  const started = await gw.startMqGateway();
  if (!started) throw new Error("网关启动失败(检查 mq.json)");
  log(`MQTT 网关已启动,客服主题 laya/cs/req → laya/cs/resp,业务默认 ${TASK},决策=${STUB ? "stub" : `真实(${API}${IS_APK ? " APK /decide" : ""})`}`);

  // 3. 测试客户端:订阅 resp + 发布 req
  client = mqtt.connect(URL_, { clientId: "cs-tester-" + Date.now() });
  await new Promise((ok, err) => { client.once("connect", ok); client.once("error", err); });
  const allDone = new Promise((ok) => {
    client.subscribe("laya/cs/resp", () => {
      client.on("message", (_t, payload) => {
        try {
          const m = JSON.parse(payload.toString("utf8"));
          results.push(m);
          if (--pending <= 0) ok();
        } catch {}
      });
    });
  });

  // 4. 抽样发布真实工单
  let tickets = loadTickets();
  if (LANG !== "any") tickets = tickets.filter((t) => t.language === LANG);
  const picks = sample(tickets, N, SEED);
  if (!picks.length) throw new Error(`无匹配工单(lang=${LANG})`);
  log(`抽样 ${picks.length} 条工单发布到 laya/cs/req …`);
  pending = picks.length;
  picks.forEach((t, i) => {
    client.publish("laya/cs/req", JSON.stringify({
      id: `t${i}`, task: TASK,
      subject: t.subject,
      body: t.body.slice(0, 400),
    }));
  });

  // 5. 等结果(总超时)
  const timer = new Promise((ok) => setTimeout(ok, 120_000));
  await Promise.race([allDone, timer]);
  if (results.length < picks.length) log(`超时:收到 ${results.length}/${picks.length}`);

  // 6. 汇总:决策 vs ground truth
  const gt = new Map(picks.map((t, i) => [`t${i}`, t]));
  let chit = 0, ctotal = 0, sErr = 0, nhit = 0, ntotal = 0, lat = [];
  console.log("\n id   | 分诊(模型→真值)      | 紧急度 | 转人工 | 延迟");
  console.log("------+------------------------+--------+--------+-------");
  for (const r of results) {
    const t = gt.get(r.id) || {};
    const a = r.answers || {};
    const choice = a.q0?.choice, score = a.q1?.score, noul = a.q2?.noul;
    const gtQ = QUEUE_MAP[t.queue] ?? t.queue;
    if (choice != null && gtQ) { ctotal++; if (choice === gtQ) chit++; }
    if (score != null && t.priority) sErr += Math.abs(score - (PRIORITY_SCORE[t.priority] ?? 3));
    if (noul != null && t.priority) { ntotal++; if ((noul >= 0.5) === (t.priority === "high")) nhit++; }
    if (r.latencyMs != null) lat.push(r.latencyMs);
    const mark = choice === gtQ ? "✓" : "✗";
    const row = `${choice ?? "?"}${mark}(${gtQ ?? "?"})`.padEnd(18);
    console.log(`${String(r.id).padEnd(5)} | ${row} | ${score ?? "?"}/${t.priority ?? "?"} | ${noul != null ? (noul >= 0.5 ? "人工" : "自动") : "?"} | ${r.latencyMs}ms`);
  }
  const avg = (xs) => xs.length ? (xs.reduce((a, b) => a + b, 0) / xs.length).toFixed(0) : "-";
  console.log(`\n汇总: ${results.length}/${picks.length} 条响应`);
  console.log(`分诊准确率: ${ctotal ? (chit / ctotal * 100).toFixed(1) + "% (" + chit + "/" + ctotal + ")" : "n/a"}`);
  console.log(`紧急度平均偏差: ${results.length ? (sErr / results.length).toFixed(2) : "-"} (1-5 分 vs priority 映射)`);
  console.log(`转人工 vs high 优先级一致率: ${ntotal ? (nhit / ntotal * 100).toFixed(1) + "%" : "n/a"}`);
  console.log(`平均延迟: ${avg(lat)} ms(含真实模型时为端到端)`);
  console.log("\n===== 回复模板 =====");
  for (const r of results) console.log(`\n[${r.id}]${r.reply || "(无)"}`);
}

try {
  await main();
} catch (e) {
  console.error("[cs-test] 失败:", e.message);
  if (!STUB && /ECONNREFUSED|fetch failed/i.test(e.message))
    console.error(`提示: 真实决策需要后端在线(${API})——Termux 起 service/server.mjs,或 app 网关页启动后用 --api http://127.0.0.1:8790;链路验证可加 --stub`);
  process.exitCode = 1;
} finally {
  // 恢复 mq.json / 关闭网关、客户端与 broker
  try {
    const gw = await import("./gateways.mjs");
    gw.stopMqGateway();
  } catch {}
  try { client?.end(true); } catch {}
  if (backup != null) fs.writeFileSync(MQ_CFG, backup); else fs.rmSync(MQ_CFG, { force: true });
  log("mq.json 已恢复");
  try { aedes?.close(); broker?.close(); } catch {}
  setTimeout(() => process.exit(process.exitCode || 0), 200).unref();
}
