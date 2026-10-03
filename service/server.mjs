#!/usr/bin/env node
// Laya 移动决策服务 — 业务自适配注册表 + 实时 API + 决策日志 + 报表
// 端口 8789(127.0.0.1)。端点:
//   GET  /health                      服务与各业务状态
//   GET  /tasks                       业务注册表(自适配结果)
//   POST /task/:id   {"text":...}     决策(写入日志)→ {answers,latencyMs}
//   GET  /report/daily|monthly|yearly [?date=YYYY-MM-DD]  报表(markdown 文本)
//   GET  /report/detail?period=daily&date=...             详单 CSV
//   POST /admin/reload               重读注册表(拖新业务后免重启)
// 业务自适配:扫描 /sdcard/models/laya-*-int8(有 laya_config.json 即为一个业务),
// 与 registry.json 合并;新增模型+重启或 POST /admin/reload 即接入。
import { LayaNative } from "../laya-native.mjs";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import * as gw from "./gateways.mjs";

const HERE = os.homedir() + "/laya-test/service";
const PORT = parseInt(process.env.PORT || "8789", 10);
const REG = JSON.parse(fs.readFileSync(path.join(HERE, "registry.json"), "utf8"));
const MODEL_ROOT = "/sdcard/models";
const LOG_DIR = path.join(HERE, "logs");
const RUNNER = os.homedir() + "/laya-test/runner";
const ORTDIR = "/data/data/com.termux/files/usr/lib/python3.12/site-packages/onnxruntime/capi";
const daemonEnv = () => ({
  ...process.env,
  LD_LIBRARY_PATH: `${ORTDIR}:${path.join(os.homedir(), "laya-test", "pylib")}:${process.env.PREFIX ?? "/data/data/com.termux/files/usr"}/lib`,
});

fs.mkdirSync(LOG_DIR, { recursive: true });

// ---------- 注册表自适配 ----------
function scanModels() {
  const found = {};
  for (const d of fs.readdirSync(MODEL_ROOT)) {
    const m = d.match(/^laya-([a-z0-9_]+)-int8$/);
    if (!m) continue;
    const id = m[1];
    const cfgPath = `${MODEL_ROOT}/${d}/laya_config.json`;
    if (!fs.existsSync(cfgPath)) continue;
    let cfg;
    try { cfg = JSON.parse(fs.readFileSync(cfgPath, "utf8")); } catch { continue; }
    let modelPath = `${MODEL_ROOT}/${d}/laya_${id}.int8.onnx`;
    if (!fs.existsSync(modelPath)) {
      const cand = fs.readdirSync(`${MODEL_ROOT}/${d}`).find((f) => f.endsWith(".int8.onnx"));
      if (!cand) continue;
      modelPath = `${MODEL_ROOT}/${d}/${cand}`;
    }
    found[id] = {
      id,
      label: REG.tasks[id]?.label ?? id,
      modelDir: `${MODEL_ROOT}/${d}`,
      modelPath,
      backend: REG.tasks[id]?.backend ?? REG.defaults.backend ?? "cpu",
      enabled: REG.tasks[id]?.enabled ?? REG.defaults.enabled ?? true,
      stateKey: Object.keys(cfg.state_format || { text: 1 })[0],
      questionDefs: cfg.question_defs || [],
      classes: cfg.classes || null,
      normalLabel: cfg.normal_label ?? null,
      source: REG.tasks[id] ? "registry" : "auto-scan",
    };
  }
  return found;
}
let TASKS = scanModels();

// ---------- 模型客户端(懒加载,常驻) ----------
const clients = new Map(); // id -> LayaNative
const loadPromises = new Map();

async function getClient(id) {
  if (clients.has(id)) return clients.get(id);
  if (loadPromises.has(id)) return loadPromises.get(id);
  const t = TASKS[id];
  if (!t) throw new Error(`未知业务: ${id}`);
  const p = (async () => {
    const c = await LayaNative.loadWithDaemon({
      modelDir: t.modelDir,
      modelPath: t.modelPath,
      socketPath: os.homedir() + `/laya-test/laya-svc-${id}.sock`,
      threads: 6,
      runnerBin: RUNNER,
      env: daemonEnv(),
    });
    clients.set(id, c);
    loadPromises.delete(id);
    return c;
  })();
  loadPromises.set(id, p);
  return p;
}

function questionsOf(t) {
  return Object.fromEntries(
    (t.questionDefs || []).map((q) => [q.qid, { type: q.type, instructions: q.instructions, criteria: q.criteria }]),
  );
}

// ---------- 决策日志(JSONL,按月分文件) ----------
function logFile(d = new Date()) {
  const ym = d.getFullYear() + String(d.getMonth() + 1).padStart(2, "0");
  return path.join(LOG_DIR, `decisions-${ym}.jsonl`);
}
function appendLog(entry) {
  fs.appendFileSync(logFile(), JSON.stringify(entry) + "\n");
}
function readLogs(rangeFilter) {
  const out = [];
  const files = fs.readdirSync(LOG_DIR).filter((f) => f.startsWith("decisions-")).sort();
  for (const f of files) {
    for (const line of fs.readFileSync(path.join(LOG_DIR, f), "utf8").split("\n")) {
      if (!line.trim()) continue;
      try {
        const e = JSON.parse(line);
        if (!rangeFilter || rangeFilter(new Date(e.ts))) out.push(e);
      } catch {}
    }
  }
  return out;
}

// ---------- 报表聚合 ----------
function pct(n, total) { return total ? ((n / total) * 100).toFixed(1) + "%" : "0%"; }

function aggregate(entries) {
  const byTask = {};
  let total = 0, latSum = 0;
  for (const e of entries) {
    total++;
    latSum += e.latencyMs || 0;
    const b = (byTask[e.task] ??= { count: 0, latSum: 0, labels: {}, risks: [] });
    b.count++;
    b.latSum += e.latencyMs || 0;
    for (const [qid, a] of Object.entries(e.answers || {})) {
      if (a.type === "choice") b.labels[a.choice] = (b.labels[a.choice] || 0) + 1;
      if (a.type === "noul") b.risks.push(a.noul);
    }
  }
  return { total, avgLatency: total ? +(latSum / total).toFixed(1) : 0, byTask };
}

function aggMarkdown(title, entries) {
  const { total, avgLatency, byTask } = aggregate(entries);
  const L = [`# ${title}`, "", `- 决策总量: **${total}** 条`, `- 平均耗时: ${avgLatency} ms`, ""];
  for (const [task, b] of Object.entries(byTask)) {
    L.push(`## ${TASKS[task]?.label ?? task}(${task})`, "", `- 数量: ${b.count}(占 ${pct(b.count, total)})`);
    if (Object.keys(b.labels).length) {
      L.push("- 判定分布:");
      for (const [k, v] of Object.entries(b.labels).sort((a, c) => c[1] - a[1]))
        L.push(`  - ${k}: ${v}(${pct(v, b.count)})`);
    }
    if (b.risks.length) {
      const avgRisk = b.risks.reduce((a, x) => a + x, 0) / b.risks.length;
      const flagged = b.risks.filter((x) => x >= 0.5).length;
      L.push(`- noul 平均概率: ${avgRisk.toFixed(3)},≥0.5 命中 ${flagged} 次(${pct(flagged, b.risks.length)})`);
    }
    L.push("");
  }
  if (!total) L.push("(该周期内无决策记录)", "");
  return L.join("\n");
}

function detailCsv(entries) {
  const rows = ["ts,task,latencyMs,answers_json,state"];
  for (const e of entries) {
    const state = (e.state || "").replace(/"/g, '""').replace(/\s+/g, " ").slice(0, 120);
    rows.push([new Date(e.ts).toISOString(), e.task, e.latencyMs ?? "", JSON.stringify(e.answers).replace(/"/g, '""'), `"${state}"`].join(","));
  }
  return rows.join("\n");
}

function rangeFor(period, dateStr) {
  const now = dateStr ? new Date(dateStr + "T00:00:00") : new Date();
  if (period === "daily") {
    const s = new Date(now); s.setHours(0, 0, 0, 0);
    const e = new Date(s); e.setDate(s.getDate() + 1);
    return [s, e];
  }
  if (period === "monthly") {
    const s = new Date(now.getFullYear(), now.getMonth(), 1);
    const e = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    return [s, e];
  }
  const s = new Date(now.getFullYear(), 0, 1);
  const e = new Date(now.getFullYear() + 1, 0, 1);
  return [s, e];
}

function reportFor(period, dateStr) {
  const [s, e] = rangeFor(period, dateStr);
  const entries = readLogs((d) => d >= s && d < e);
  const titles = { daily: "日报", monthly: "月报", yearly: "年报" };
  const ds = s.toISOString().slice(0, 10);
  return aggMarkdown(`${ds} ${titles[period]}`, entries);
}

function detailFor(period, dateStr) {
  const [s, e] = rangeFor(period, dateStr);
  return detailCsv(readLogs((d) => d >= s && d < e));
}

// ---------- HTTP ----------
const json = (res, code, obj) => {
  const body = typeof obj === "string" ? obj : JSON.stringify(obj, null, 2);
  res.writeHead(code, { "Content-Type": typeof obj === "string" ? "text/plain; charset=utf-8" : "application/json" });
  res.end(body);
};

function readBody(req) {
  return new Promise((resolve, reject) => {
    let b = "";
    req.on("data", (c) => (b += c));
    req.on("end", () => resolve(b));
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  try {
    if (req.method === "GET" && url.pathname === "/health") {
      return json(res, 200, {
        ok: true, service: "laya-mobile-decision",
        tasks: Object.fromEntries(Object.entries(TASKS).map(([id, t]) => [id, { enabled: t.enabled, loaded: clients.has(id), backend: t.backend }])),
      });
    }
    if (req.method === "GET" && url.pathname === "/tasks") {
      return json(res, 200, {
        tasks: Object.values(TASKS).filter((t) => t.enabled).map(({ id, label, stateKey, backend, questionDefs, classes, source }) => ({
          id, label, stateKey, backend,
          questions: questionDefs.map((q) => ({ qid: q.qid, type: q.type, instructions: q.instructions })),
          classes, source,
        })),
      });
    }
    if (req.method === "POST" && url.pathname.startsWith("/task/")) {
      const id = url.pathname.split("/")[2];
      const t = TASKS[id];
      if (!t || !t.enabled) return json(res, 404, { error: `业务 ${id} 不存在或未启用`, available: Object.keys(TASKS) });
      const body = JSON.parse((await readBody(req)) || "{}");
      const text = String(body.text ?? "").trim();
      if (!text) return json(res, 400, { error: "text 不能为空" });
      const client = await getClient(id);
      const t0 = performance.now();
      const r = await client.systemOne({ [t.stateKey]: text }, questionsOf(t));
      const latencyMs = Math.round(performance.now() - t0);
      const entry = { ts: Date.now(), task: id, state: text.slice(0, 200), answers: r.answers, latencyMs };
      appendLog(entry);
      return json(res, 200, { task: id, answers: r.answers, usage: r.usage, latencyMs });
    }
    if (req.method === "GET" && url.pathname.startsWith("/report/")) {
      const [, , kind] = url.pathname.split("/");
      if (kind === "detail") {
        const csv = detailFor(url.searchParams.get("period") || "daily", url.searchParams.get("date"));
        res.writeHead(200, { "Content-Type": "text/csv; charset=utf-8" });
        return res.end(csv);
      }
      if (!["daily", "monthly", "yearly"].includes(kind)) return json(res, 404, { error: "period 须为 daily|monthly|yearly" });
      return json(res, 200, { period: kind, report: reportFor(kind, url.searchParams.get("date")) });
    }
    if (req.method === "POST" && url.pathname === "/admin/reload") {
      TASKS = scanModels();
      return json(res, 200, { reloaded: true, tasks: Object.keys(TASKS) });
    }
    // ---- 网关管理(APK 网关页) ----
    if (req.method === "GET" && url.pathname === "/admin/gateways") {
      const em = gw.readEmailConfig();
      const mq = gw.readMqConfig();
      const mask = (o) => o && { ...o, imap: o.imap && { ...o.imap, pass: o.imap.pass ? "***" : "" }, smtp: o.smtp && { ...o.smtp, pass: o.smtp.pass ? "***" : "" } };
      return json(res, 200, {
        email: { configured: !!em, enabled: !!em?.enabled, host: em?.imap?.host, user: em?.imap?.user, reportTo: em?.report?.to, pollIntervalSec: em?.poll?.intervalSec },
        mq: { configured: !!mq, enabled: !!mq?.enabled, url: mq?.mqtt?.url, sub: mq?.topics?.sub, pub: mq?.topics?.pub },
      });
    }
    if (req.method === "POST" && url.pathname === "/admin/email-config") {
      const cfg = JSON.parse((await readBody(req)) || "{}");
      if (!cfg.imap?.host || !cfg.imap?.user || !cfg.smtp?.host) return json(res, 400, { error: "需要 imap.{host,user,pass} 与 smtp.{host,user,pass}" });
      gw.writeEmailConfig({ enabled: cfg.enabled !== false, imap: cfg.imap, smtp: cfg.smtp, poll: cfg.poll ?? { intervalSec: 60 }, report: cfg.report ?? {} });
      gw.stopEmailGateway();
      const ok = await gw.startEmailGateway().catch((e) => ({ error: e.message }));
      return json(res, 200, { saved: true, started: ok === true, detail: ok === true ? null : ok });
    }
    if (req.method === "POST" && url.pathname === "/admin/mq-config") {
      const cfg = JSON.parse((await readBody(req)) || "{}");
      if (!cfg.mqtt?.url) return json(res, 400, { error: "需要 mqtt.url" });
      gw.writeMqConfig({ enabled: cfg.enabled !== false, mqtt: cfg.mqtt, topics: cfg.topics ?? { sub: "laya/req/+", pub: "laya/resp" } });
      gw.stopMqGateway();
      const ok = await gw.startMqGateway().catch((e) => ({ error: e.message }));
      return json(res, 200, { saved: true, started: ok === true, detail: ok === true ? null : ok });
    }
    if (req.method === "POST" && url.pathname === "/admin/email-test") {
      const body = JSON.parse((await readBody(req)) || "{}");
      await gw.emailTestSend(body.to, body.text);
      return json(res, 200, { sent: true, to: body.to });
    }
    json(res, 404, { error: "not found" });
  } catch (err) {
    json(res, 500, { error: String(err.message || err) });
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`[laya-svc] http://127.0.0.1:${PORT}  业务: ${Object.keys(TASKS).join(", ")}`);
  console.log(`[laya-svc] 端点: GET /tasks | POST /task/:id | GET /report/{daily,monthly,yearly} | GET /report/detail | POST /admin/reload`);
  // 网关启动(配置存在才真正启动)
  gw.setDecideFn(async (task, text) => {
    const t = TASKS[task];
    if (!t) throw new Error(`未知业务 ${task}(可用: ${Object.keys(TASKS).join(",")})`);
    const client = await getClient(task);
    const t0 = performance.now();
    const r = await client.systemOne({ [t.stateKey]: text }, questionsOf(t));
    const latencyMs = Math.round(performance.now() - t0);
    appendLog({ ts: Date.now(), task, state: text.slice(0, 200), answers: r.answers, latencyMs, via: "gateway" });
    return { answers: r.answers, latencyMs };
  });
  gw.startEmailGateway().catch((e) => console.log("[laya-svc] 邮件网关启动失败:", e.message));
  gw.startMqGateway().catch((e) => console.log("[laya-svc] MQTT 网关启动失败:", e.message));
  // 日报定时:每天 08:05 生成昨日日报,配置了 report.to 就发邮件
  let lastDaily = "";
  setInterval(() => {
    const now = new Date();
    const key = now.toISOString().slice(0, 10);
    if (now.getHours() === 8 && now.getMinutes() === 5 && lastDaily !== key) {
      lastDaily = key;
      try {
        const y = new Date(now); y.setDate(y.getDate() - 1);
        const report = reportFor("daily", y.toISOString().slice(0, 10));
        const cfg = gw.readEmailConfig();
        if (cfg?.report?.to) {
          gw.emailTestSend(cfg.report.to, report).catch((e) => console.log("[laya-svc] 日报邮件失败:", e.message));
        }
        fs.writeFileSync(path.join(LOG_DIR, `report-daily-${key}.md`), report);
        console.log("[laya-svc] 日报已生成");
      } catch (e) { console.log("[laya-svc] 日报生成失败:", e.message); }
    }
  }, 60_000);
});

// 优雅退出:杀掉所有模型 runner
for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, () => {
    for (const [, c] of clients) { try { c.shutdown(); } catch {} }
    process.exit(0);
  });
}
