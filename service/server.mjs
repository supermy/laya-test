#!/usr/bin/env node
// Laya 移动决策服务 — 业务自适配注册表 + 实时 API + 决策日志 + 报表
// 端口 8789(127.0.0.1)。端点:
//   GET  /health                      服务与各业务状态
//   GET  /tasks                       业务注册表(自适配结果)
//   POST /task/:id   {"text":...}     决策(写入日志)→ {answers,latencyMs}
//   GET  /report/daily|monthly|yearly [?date=YYYY-MM-DD]  报表(markdown 文本)
//   GET  /report/detail?period=daily&date=...             详单 CSV
//   GET  /report/page                                     详单页面(业务×决策等级×日期,可下钻)
//   GET  /report/query?from&to[&task&level]               聚合行 JSON(date,task,level,count)
//   GET  /report/entries?date|from&to[&task&level&limit]  下钻明细 JSON
//   POST /admin/reload               重读注册表(拖新业务后免重启)
// 业务自适配:扫描 /sdcard/models/laya-*-int8(有 laya_config.json 即为一个业务),
// 与 registry.json 合并;新增模型+重启或 POST /admin/reload 即接入。
import { LayaNative } from "../laya-native.mjs";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import * as gw from "./gateways.mjs";
import { replyAppendix, setTemplateLookup, templateSpecs } from "./reply-templates.mjs";
import { openDb, insertDecision, upsertTasks, queryDecisions, levelOfUnified as levelOf,
         listTemplates, upsertTemplate, deleteTemplate, getCollect, setCollect, listInbound } from "./db.mjs";

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
// ---------- 统一存储(SQLite;JSONL 已停写,存量迁移用 migrate-jsonl.mjs) ----------
const db = openDb();

let TASKS = scanModels();
upsertTasks(db, TASKS); // 注册表落库(内存 TASKS 仍为引擎路由权威)

// ---------- 模型客户端(懒加载,常驻) ----------
const clients = new Map(); // id -> LayaNative
const loadPromises = new Map();

async function getClient(id) {
  if (clients.has(id)) return clients.get(id);
  if (loadPromises.has(id)) return loadPromises.get(id);
  const t = TASKS[id];
  if (!t) throw new Error(`未知业务: ${id}`);
  const p = (async () => {
    try {
      const c = await LayaNative.loadWithDaemon({
        modelDir: t.modelDir,
        modelPath: t.modelPath,
        socketPath: os.homedir() + `/laya-test/laya-svc-${id}.sock`,
        threads: 6,
        runnerBin: RUNNER,
        env: daemonEnv(),
      });
      c._loadedAt = Date.now(); // 供 /admin/reload 判断模型文件是否更新过
      clients.set(id, c);
      return c;
    } catch (e) {
      loadPromises.delete(id); // 失败不留污染,下次请求可重试
      throw e;
    }
  })();
  loadPromises.set(id, p);
  return p;
}

function questionsOf(t) {
  return Object.fromEntries(
    (t.questionDefs || []).map((q) => [q.qid, { type: q.type, instructions: q.instructions, criteria: q.criteria }]),
  );
}

// ---------- 决策日志(SQLite,见 db.mjs/schema.sql;存量迁移用 migrate-jsonl.mjs) ----------
// 旧 readLogs 等价物:本地日期闭开区间 [s, e) 的决策行(Date 对象入参)
const readEntries = (s, e, opts = {}) => queryDecisions(db, { fromDate: localDate(s.getTime()), toDate: localDate(e.getTime()), ...opts });

// ---------- 模板覆盖(templates 表 → reply-templates 查找层) ----------
const tplCache = new Map(); // "task|locale|kind|key" -> body
function loadTplCache() {
  tplCache.clear();
  for (const r of db.prepare("SELECT task,locale,kind,key,body FROM templates").all()) tplCache.set(`${r.task}|${r.locale}|${r.kind}|${r.key}`, r.body);
}
loadTplCache();
setTemplateLookup((task, locale, kind, key) => tplCache.get(`${task}|${locale}|${kind}|${key}`) ?? null);

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
    rows.push([new Date(e.ts).toISOString(), e.task, e.latencyMs ?? "", `"${JSON.stringify(e.answers).replace(/"/g, '""')}"`, `"${state}"`].join(","));
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
  const entries = readEntries(s, e);
  const titles = { daily: "日报", monthly: "月报", yearly: "年报" };
  const ds = s.toISOString().slice(0, 10);
  return aggMarkdown(`${ds} ${titles[period]}`, entries);
}

function detailFor(period, dateStr) {
  const [s, e] = rangeFor(period, dateStr);
  return detailCsv(readEntries(s, e));
}

// ---------- 决策后任务等级 + 下钻详单 ----------
// 等级口径 levelOf 统一实现见 db.mjs(levelOfUnified):score 归一化 / noul>=0.5 命中=高 / choice 标签。

const localDate = (ts) => {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};
const localTime = (ts) => {
  const d = new Date(ts);
  return `${localDate(ts)} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}:${String(d.getSeconds()).padStart(2, "0")}`;
};

function queryRows(from, to, task, level) {
  const s = new Date(from + "T00:00:00");
  const e = new Date(to + "T00:00:00"); e.setDate(e.getDate() + 1);
  const agg = new Map(); // date|task|level -> {count,latSum}
  let total = 0;
  for (const raw of readEntries(s, e)) {
    if (task && raw.task !== task) continue;
    const { level: lv } = levelOf(raw.answers);
    if (level && lv !== level) continue;
    const k = `${localDate(raw.ts)}|${raw.task}|${lv}`;
    if (!agg.has(k)) agg.set(k, { count: 0, latSum: 0 });
    const b = agg.get(k);
    b.count++; b.latSum += raw.latencyMs || 0; total++;
  }
  const rows = [...agg.entries()].map(([k, b]) => {
    const [date, t, lv] = k.split("|");
    return { date, task: t, level: lv, count: b.count, avgLatency: +(b.latSum / b.count).toFixed(0) };
  }).sort((a, c) => a.date < c.date ? -1 : a.date > c.date ? 1 : a.task < c.task ? -1 : a.level < c.level ? -1 : 1);
  return { from, to, task: task || null, level: level || null, total, rows };
}

function drillEntries(date, task, level, limit = 200) {
  let s, e;
  if (date) {
    s = new Date(date + "T00:00:00");
    e = new Date(date + "T00:00:00"); e.setDate(e.getDate() + 1);
  } else {
    s = new Date("1970-01-01T00:00:00");
    e = new Date(); e.setHours(24, 0, 0, 0);
  }
  const out = [];
  for (const raw of readEntries(s, e, { orderDesc: true })) {
    if (task && raw.task !== task) continue;
    const { level: lv, basis } = levelOf(raw.answers);
    if (level && lv !== level) continue;
    const parts = [];
    for (const a of Object.values(raw.answers || {})) {
      if (a.type === "choice") parts.push(a.choice);
      else if (a.type === "score") parts.push(`${(a.score ?? 0).toFixed(1)}分`);
      else if (a.type === "noul") parts.push(a.noul >= 0.5 ? "需人工" : "自动");
    }
    out.push({ time: localTime(raw.ts), task: raw.task, level: lv, basis, summary: parts.join(" | "), state: raw.state, answers: raw.answers, latencyMs: raw.latencyMs });
    if (out.length >= limit) break;
  }
  return { date: date || null, task: task || null, level: level || null, count: out.length, entries: out };
}

// ---- 样本/评测数据导入(eval_samples) ----
function parseCsvLine(line) {
  const out = []; let cur = "", inQ = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inQ) {
      if (c === '"') { if (line[i + 1] === '"') { cur += '"'; i++; } else inQ = false; }
      else cur += c;
    } else {
      if (c === '"') inQ = true;
      else if (c === ",") { out.push(cur); cur = ""; }
      else cur += c;
    }
  }
  out.push(cur);
  return out;
}

/** body: {file:"tickets.csv"}(service 目录下,仅文件名) 或 {samples:[{text,expectTask?,expectLabel?,meta?}]} */
function importEvalSamples(b) {
  let rows;
  if (Array.isArray(b.samples)) {
    rows = b.samples.map((s, i) => ({ extId: String(s.extId ?? i), text: String(s.text ?? ""), expectTask: s.expectTask ?? "", expectLabel: s.expectLabel ?? "", meta: s.meta ?? {} }));
  } else if (b.file) {
    const name = path.basename(String(b.file));
    if (name !== b.file) throw new Error("file 只接受文件名");
    // 允许 service/ 与仓库根两处
    const cand = [path.join(HERE, name), path.join(HERE, "..", name)].find((p) => fs.existsSync(p));
    if (!cand) throw new Error(`文件不存在: ${name}(service/ 或仓库根)`);
    const lines = fs.readFileSync(cand, "utf8").split("\n").filter((l) => l.trim());
    const header = parseCsvLine(lines[0]).map((h) => h.trim());
    rows = lines.slice(1).map((line, i) => {
      const c = parseCsvLine(line);
      const col = (k) => header.includes(k) ? (c[header.indexOf(k)] ?? "") : "";
      const meta = {};
      header.forEach((h, j) => { if (!["subject", "body", "answer", "type", "queue"].includes(h)) meta[h] = c[j] ?? ""; });
      const text = [col("subject"), col("body")].filter(Boolean).join("\n");
      return { extId: String(i + 1), text, expectTask: col("queue") || col("type"), expectLabel: col("answer"), meta };
    });
  } else throw new Error("需要 file 或 samples");
  const ins = db.prepare(
    `INSERT OR IGNORE INTO eval_samples(source,ext_id,text,expect_task,expect_label,meta,created_at) VALUES(?,?,?,?,?,?,?)`
  );
  let imported = 0;
  const src = b.source || "tickets";
  for (const r of rows) {
    if (!r.text) continue;
    imported += ins.run(src, r.extId, r.text, r.expectTask || "", r.expectLabel || "", JSON.stringify(r.meta ?? {}), Date.now()).changes;
  }
  return { source: src, total: rows.length, imported, skipped: rows.length - imported };
}

const REPORT_PAGE = `<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>决策详单 · 分业务分等级</title>
<style>
body{font-family:system-ui,sans-serif;margin:12px;background:#f6f7fa;color:#1a1a2e;font-size:14px}
h2{margin:14px 0 8px}
.bar{display:flex;flex-wrap:wrap;gap:8px;align-items:center;background:#fff;padding:10px;border-radius:10px;border:1px solid #e1e4eb}
select,input{padding:6px 8px;border:1px solid #cfd4de;border-radius:8px;background:#fff}
button{padding:7px 16px;border:0;border-radius:8px;background:#4361ee;color:#fff}
table{border-collapse:collapse;width:100%;background:#fff;border-radius:10px;overflow:hidden;margin-top:10px}
th,td{border-bottom:1px solid #e9ecf2;padding:7px 10px;text-align:left;white-space:nowrap}
th{background:#eef1f7}
td.clk{cursor:pointer;color:#4361ee;font-weight:600}
td.clk:hover{background:#f0f4ff}
tr:hover td{background:#fafbfe}
small{color:#6e7382}
.lv-高{color:#d62828;font-weight:700}.lv-中{color:#e78a00;font-weight:700}.lv-低{color:#2a9d8f;font-weight:700}
#drill{margin-top:8px}
.card{background:#fff;border:1px solid #e9ecf2;border-radius:10px;padding:8px 10px;margin-top:8px}
.card .l1{font-weight:700;font-size:13px}
.card .l2{font-size:12px;color:#444a55;margin-top:2px}
.card .l3{font-size:12px;color:#6e7382;margin-top:2px;word-break:break-all}
details{margin-top:4px}details summary{cursor:pointer;color:#4361ee}
pre{white-space:pre-wrap;word-break:break-all;background:#f2f4f8;padding:8px;border-radius:8px;font-size:12px}
</style></head><body>
<h2>决策详单 <small>分业务 × 决策等级 × 日期,点击数字下钻</small></h2>
<div class="bar">
<label>快捷 <select id="preset" onchange="preset()">
<option value="7">近7天</option><option value="1">今天</option><option value="30">近30天</option><option value="0">全部</option>
</select></label>
<label>从 <input type="date" id="from"></label>
<label>到 <input type="date" id="to"></label>
<label>业务 <select id="task"><option value="">全部</option></select></label>
<label>等级 <select id="level"><option value="">全部</option><option>高</option><option>中</option><option>低</option></select></label>
<button onclick="load()">查询</button>
<span id="sum"></span>
</div>
<div id="byLevel"></div>
<div id="byDate"></div>
<div id="drill"></div>
<script>
var LEVELS = ["高","中","低"];
function pad(n){return (n<10?"0":"")+n}
function iso(d){return d.getFullYear()+"-"+pad(d.getMonth()+1)+"-"+pad(d.getDate())}
function preset(){
  var v=parseInt(document.getElementById("preset").value,10), t=new Date();
  var s=new Date();
  if(v>0)s.setDate(s.getDate()-(v-1));
  document.getElementById("to").value=iso(t);
  document.getElementById("from").value=v>0?iso(s):"2020-01-01";
}
function init(){
  document.getElementById("preset").value="7"; preset();
  fetch("/tasks").then(function(r){return r.json()}).then(function(j){
    var sel=document.getElementById("task");
    j.tasks.forEach(function(t){var o=document.createElement("option");o.value=t.id;o.textContent=t.label+" ("+t.id+")";sel.appendChild(o)});
  });
  load();
}
function esc(s){var d=document.createElement("div");d.textContent=s==null?"":String(s);return d.innerHTML}
function load(){
  var q="from="+document.getElementById("from").value+"&to="+document.getElementById("to").value;
  var tk=document.getElementById("task").value, lv=document.getElementById("level").value;
  if(tk)q+="&task="+encodeURIComponent(tk); if(lv)q+="&level="+encodeURIComponent(lv);
  fetch("/report/query?"+q).then(function(r){return r.json()}).then(render);
  document.getElementById("drill").innerHTML="";
}
function render(j){
  document.getElementById("sum").textContent="共 "+j.total+" 条";
  var tasks={},levels={},dates={};
  j.rows.forEach(function(r){tasks[r.task]=1;dates[r.date]=1;levels[r.level]=1});
  var tks=Object.keys(tasks).sort(), dts=Object.keys(dates).sort().reverse();
  var lvs=LEVELS.filter(function(l){return levels[l]}).concat(Object.keys(levels).filter(function(l){return LEVELS.indexOf(l)<0}).sort());
  // 聚合:表1 按 task(等级分列),表2 按 date(业务分列)——多等级/多业务都要累加
  var byLevel={},byDate={};
  j.rows.forEach(function(r){
    var b1=(byLevel[r.task]=byLevel[r.task]||{}); b1[r.level]=(b1[r.level]||0)+r.count; b1.count=(b1.count||0)+r.count;
    var b2=(byDate[r.date]=byDate[r.date]||{}); b2[r.task]=(b2[r.task]||0)+r.count; b2.count=(b2.count||0)+r.count;
  });
  // 表1:业务 × 等级
  var h="<h2>业务 × 决策等级(区间合计,点击下钻)</h2><table><tr><th>业务</th>";
  lvs.forEach(function(l){h+="<th>"+esc(l)+"</th>"}); h+="<th>合计</th></tr>";
  tks.forEach(function(t){
    var b=byLevel[t]||{},tot=0; h+="<tr><td>"+esc(t)+"</td>";
    lvs.forEach(function(l){var n=b[l]||0;tot+=n;h+="<td class='clk' onclick=\\"drill(null,'"+esc(t)+"','"+esc(l)+"')\\">"+(n||"<span style='color:#ccc'>·</span>")+"</td>"});
    h+="<td class='clk' onclick=\\"drill(null,'"+esc(t)+"',null)\\"><b>"+tot+"</b></td></tr>";
  });
  document.getElementById("byLevel").innerHTML=h+"</table>";
  // 表2:日期 × 业务
  h="<h2>按日期(点击下钻)</h2><table><tr><th>日期</th>";
  tks.forEach(function(t){h+="<th>"+esc(t)+"</th>"}); h+="<th>合计</th></tr>";
  dts.forEach(function(d){
    var b=byDate[d]||{},tot=0; h+="<tr><td>"+esc(d)+"</td>";
    tks.forEach(function(t){var n=b[t]||0;tot+=n;h+="<td class='clk' onclick=\\"drill('"+esc(d)+"','"+esc(t)+"',null)\\">"+(n||"<span style='color:#ccc'>·</span>")+"</td>"});
    h+="<td class='clk' onclick=\\"drill('"+esc(d)+"',null,null)\\"><b>"+b.count||0+"</b></td></tr>";
  });
  document.getElementById("byDate").innerHTML=h+"</table>";
}
function ansSummary(a){
  var parts=[];
  for(var k in a){var x=a[k];if(!x||!x.type)continue;
    if(x.type=="choice")parts.push(x.choice||"?");
    else if(x.type=="score")parts.push((x.score!=null?x.score.toFixed(1):"?")+"分");
    else if(x.type=="noul")parts.push(x.noul>=0.5?"需人工":"自动");}
  return parts.join(" | ");
}
function drill(date,task,level){
  var q=[], from=null, to=null;
  if(date)q.push("date="+date); else {
    from=document.getElementById("from").value; to=document.getElementById("to").value;
    q.push("from="+from);q.push("to="+to);
  }
  if(task)q.push("task="+encodeURIComponent(task)); if(level)q.push("level="+encodeURIComponent(level));
  document.getElementById("drill").innerHTML="<h2>详单加载中…</h2>";
  fetch("/report/entries?"+q.join("&")).then(function(r){return r.json()}).then(function(j){
    var h="<h2>详单 "+(date?esc(date):esc(from)+" ~ "+esc(to))+" · "+(task?esc(task):"全部业务")+" · "+(level?esc(level):"全部等级")+" · "+j.count+" 条</h2>";
    if(!j.entries.length){document.getElementById("drill").innerHTML=h+"<p><small>无记录</small></p>";return}
    var wrap="<div id='drill'>";
    j.entries.forEach(function(e){
      var lv=esc(e.level);
      wrap+="<div class='card'><div class='l1'>"+esc(e.time)+"  ["+esc(e.task)+"]  <span class='lv-"+lv+"'>"+lv+"</span>  "+esc(e.latencyMs)+"ms</div>"+
        "<div class='l2'>"+esc(e.summary||"")+(e.summary&&e.basis?" | ":"")+esc(e.basis||"")+"</div>"+
        "<div class='l3'>"+esc((e.state||"").slice(0,80))+
        "<details><summary> answers</summary><pre>"+esc(JSON.stringify(e.answers,null,1))+"</pre></details></div></div>";
    });
    document.getElementById("drill").innerHTML=h+wrap;
  });
}
init();
</script></body></html>`;


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
      const entry = { ts: Date.now(), task: id, state: text.slice(0, 200), answers: r.answers, latencyMs, via: "api" };
      insertDecision(db, entry);
      return json(res, 200, { task: id, answers: r.answers, usage: r.usage, latencyMs, reply: replyAppendix(id, r.answers, text) });
    }
    if (req.method === "GET" && url.pathname.startsWith("/report/")) {
      const [, , kind] = url.pathname.split("/");
      if (kind === "page") {
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        return res.end(REPORT_PAGE);
      }
      if (kind === "query") {
        const p = url.searchParams;
        const to = p.get("to") || localDate(Date.now());
        const from = p.get("from") || localDate(Date.now() - 6 * 86400e3);
        return json(res, 200, queryRows(from, to, p.get("task") || null, p.get("level") || null));
      }
      if (kind === "entries") {
        const p = url.searchParams;
        return json(res, 200, drillEntries(p.get("date"), p.get("task") || null, p.get("level") || null, parseInt(p.get("limit") || "200", 10)));
      }
      if (kind === "detail") {
        const csv = detailFor(url.searchParams.get("period") || "daily", url.searchParams.get("date"));
        res.writeHead(200, { "Content-Type": "text/csv; charset=utf-8" });
        return res.end(csv);
      }
      if (!["daily", "monthly", "yearly"].includes(kind)) return json(res, 404, { error: "period 须为 daily|monthly|yearly" });
      return json(res, 200, { period: kind, report: reportFor(kind, url.searchParams.get("date")) });
    }
    if (req.method === "POST" && url.pathname === "/admin/reload") {
      const next = scanModels();
      const evicted = [];
      // 停掉失效 client(业务被移除/禁用,或模型文件在加载后被重新导出);
      // shutdown 会给 daemon 发 exit 帧,下次请求懒加载自动拉起新权重
      for (const [id, c] of [...clients]) {
        const t = next[id];
        let mtime = 0;
        try { mtime = fs.statSync(t.modelPath).mtimeMs; } catch {}
        if (!t || !t.enabled || mtime > (c._loadedAt || 0)) {
          try { c.shutdown(); } catch {}
          clients.delete(id);
          evicted.push(id);
        }
      }
      for (const id of [...loadPromises.keys()]) if (!next[id]) loadPromises.delete(id);
      TASKS = next;
      upsertTasks(db, TASKS);
      return json(res, 200, { reloaded: true, tasks: Object.keys(TASKS), evicted });
    }
    // ---- 数据管理(模板/采集配置/入站留痕/样本导入) ----
    if (url.pathname === "/templates") {
      if (req.method === "GET") {
        if (url.searchParams.get("specs")) return json(res, 200, { specs: templateSpecs() });
        return json(res, 200, { templates: listTemplates(db, { task: url.searchParams.get("task") || undefined, locale: url.searchParams.get("locale") || undefined }) });
      }
      if (req.method === "PUT") {
        const b = JSON.parse((await readBody(req)) || "{}");
        upsertTemplate(db, b);
        loadTplCache();
        return json(res, 200, { saved: true });
      }
      if (req.method === "DELETE") {
        const n = deleteTemplate(db, parseInt(url.searchParams.get("id"), 10));
        loadTplCache();
        return json(res, 200, { deleted: n });
      }
    }
    if (req.method === "GET" && url.pathname === "/collect") return json(res, 200, getCollect(db));
    if (req.method === "PUT" && url.pathname === "/collect") {
      const b = JSON.parse((await readBody(req)) || "{}");
      if (b.intervalSec != null && b.intervalSec < 15) return json(res, 400, { error: "intervalSec ≥ 15" });
      setCollect(db, b);
      return json(res, 200, { saved: true, ...getCollect(db) });
    }
    if (req.method === "GET" && url.pathname === "/inbound") {
      const p = url.searchParams;
      return json(res, 200, { inbound: listInbound(db, { from: p.get("from"), to: p.get("to"), channel: p.get("channel"), status: p.get("status"), limit: parseInt(p.get("limit") || "100", 10) }) });
    }
    if (req.method === "POST" && url.pathname === "/eval/import") {
      const b = JSON.parse((await readBody(req)) || "{}");
      return json(res, 200, importEvalSamples(b));
    }
    // ---- 网关管理(APK 网关页) ----
    if (req.method === "GET" && url.pathname === "/admin/gateways") {
      const em = gw.readEmailConfig();
      const mq = gw.readMqConfig();
      const mask = (o) => o && { ...o, imap: o.imap && { ...o.imap, pass: o.imap.pass ? "***" : "" }, smtp: o.smtp && { ...o.smtp, pass: o.smtp.pass ? "***" : "" } };
      return json(res, 200, {
        email: { configured: !!em, enabled: !!em?.enabled, host: em?.imap?.host, user: em?.imap?.user, reportTo: em?.report?.to, pollIntervalSec: em?.poll?.intervalSec },
        mq: { configured: !!mq, enabled: !!mq?.enabled, url: mq?.mqtt?.url, sub: mq?.topics?.sub, pub: mq?.topics?.pub, csSub: mq?.topics?.csSub ?? "laya/cs/req", csPub: mq?.topics?.csPub ?? "laya/cs/resp", csTask: mq?.topics?.csTask ?? "ticket" },
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
  console.log(`[laya-svc] 端点: GET /tasks | POST /task/:id | GET /report/{daily,monthly,yearly} | GET /report/page(详单页) | GET /report/detail | POST /admin/reload`);
  // 网关启动(配置存在才真正启动)
  gw.setDecideFn(async (task, text) => {
    const t = TASKS[task];
    if (!t) throw new Error(`未知业务 ${task}(可用: ${Object.keys(TASKS).join(",")})`);
    const client = await getClient(task);
    const t0 = performance.now();
    const r = await client.systemOne({ [t.stateKey]: text }, questionsOf(t));
    const latencyMs = Math.round(performance.now() - t0);
    const decisionId = Number(insertDecision(db, { ts: Date.now(), task, state: text.slice(0, 200), answers: r.answers, latencyMs, via: "gateway" }));
    return { answers: r.answers, latencyMs, reply: replyAppendix(task, r.answers, text), decisionId };
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
