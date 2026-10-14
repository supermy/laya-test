#!/usr/bin/env node
// 资讯(news)与客服(ticket)决策对比:同后端 /decide(默认 APK 8790 NPU)各跑 n 条,
// 汇总延迟(均值/中位/最慢)、判定分布、紧急度、模板结构(自动 vs 人工)。
// 用法: node service/compare-biz.mjs [--n 12] [--api http://127.0.0.1:8790]
import fs from "node:fs";
import path from "node:path";

const args = process.argv.slice(2);
const opt = (k, d) => {
  const i = args.indexOf("--" + k);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith("--") ? args[i + 1] : d;
};
const N = parseInt(opt("n", "12"), 10);
const API = (opt("api", "http://127.0.0.1:8790")).replace(/\/$/, "");
const log = (...a) => console.log(...a);

// 资讯语料:12 条中文财经快讯(利好/利空/中性 各4,沿用 probe-news2 风格)
const NEWS = [
  ["某公司公告:获得国家科技进步一等奖,并中标15亿元智慧城市大单。", "利好"],
  ["央行宣布下调存款准备金率0.5个百分点,释放长期流动性约1万亿元。", "利好"],
  ["某公司与海外客户签署长期供货协议,合同金额占去年营收的60%。", "利好"],
  ["某公司公告:控股股东拟增持不低于2亿元公司股份。", "利好"],
  ["某公司公告:因涉嫌信息披露违法违规,证监会决定对公司立案调查。", "利空"],
  ["某公司三季报:净利润同比下滑47%,主要产品价格持续走低。", "利空"],
  ["某公司公告:实际控制人被公安机关采取强制措施。", "利空"],
  ["某公司年报:计提大额资产减值准备,全年由盈转亏。", "利空"],
  ["某公司公告:今日股票停牌一天,将于明日披露相关事项。", "中性"],
  ["某公司发布公告称,董事会审议通过了修改公司章程的议案。", "中性"],
  ["某公司公告:将于下月召开年度股东大会,审议常规议案。", "中性"],
  ["某公司披露:使用部分闲置募集资金进行现金管理。", "中性"],
];

// 客服语料:tickets.csv 抽样(en,固定种子可复现)
function loadTickets(n, seed = 42) {
  const txt = fs.readFileSync(path.join(path.dirname(new URL(import.meta.url).pathname), "..", "tickets.csv"), "utf8");
  const rows = []; let f = [], cur = "", inq = false;
  for (let i = 0; i < txt.length; i++) {
    const c = txt[i];
    if (inq) { if (c === '"') { if (txt[i + 1] === '"') { cur += '"'; i++; } else inq = false; } else cur += c; }
    else if (c === '"') inq = true;
    else if (c === ",") { f.push(cur); cur = ""; }
    else if (c === "\n") { rows.push(f); f = []; cur = ""; }
    else if (c !== "\r") cur += c;
  }
  const hdr = rows[0];
  const lang = hdr.indexOf("language"), sub = hdr.indexOf("subject"), body = hdr.indexOf("body"), q = hdr.indexOf("queue");
  const pool = rows.slice(1).filter((r) => r.length > 8 && r[lang] === "en" && r[sub].trim())
    .map((r) => r[sub] + "\n" + r[body].slice(0, 400));
  let s = seed; const rnd = () => (s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  const out = [];
  while (out.length < n && pool.length) out.push(pool.splice(Math.floor(rnd() * pool.length), 1)[0]);
  return out;
}

async function decide(task, text) {
  const r = await fetch(`${API}/decide`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ task, text }),
  });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}
const lat = (xs) => xs.slice().sort((a, b) => a - b);
const avg = (xs) => (xs.reduce((a, b) => a + b, 0) / xs.length).toFixed(0);
const p = (xs, q) => xs[Math.min(xs.length - 1, Math.floor(q * xs.length))];

async function runBiz(task, items) {
  const lat_ = [], dist = {}, tpl = { auto: 0, human: 0 };
  for (const text of items) {
    const j = await decide(task, text);
    lat_.push(j.latencyMs);
    const a = j.answers || {};
    const main = a.q0 || Object.values(a)[0] || {};
    const key = main.choice ?? (main.score != null ? `${Math.trunc(main.score)}分` : "?");
    dist[key] = (dist[key] || 0) + 1;
    if ((j.reply || "").includes("【自动回复】")) tpl.auto++;
    else if ((j.reply || "").includes("【人工")) tpl.human++;
  }
  return { lat_, dist, tpl };
}

const news = await runBiz("news", NEWS.map(([t]) => t));
const ticket = await runBiz("ticket", loadTickets(N));
const nl = lat(news.lat_), tl = lat(ticket.lat_);
const distStr = (d) => Object.entries(d).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}:${v}`).join(" ");

console.log(`\n## 资讯 vs 客服 决策对比(后端 ${API},各 ${N} 条)\n`);
console.log("| 维度 | 资讯 news | 客服 ticket |");
console.log("|---|---|---|");
console.log(`| 输入 | 中文财经快讯(12条) | tickets.csv 英文工单 |`);
console.log(`| 平均延迟 | ${avg(nl)} ms | ${avg(tl)} ms |`);
console.log(`| 中位延迟 | ${p(nl, 0.5)} ms | ${p(tl, 0.5)} ms |`);
console.log(`| 最慢 | ${nl[nl.length - 1]} ms | ${tl[tl.length - 1]} ms |`);
console.log(`| 判定分布 | ${distStr(news.dist)} | ${distStr(ticket.dist)} |`);
console.log(`| 模板结构 | 自动 ${news.tpl.auto} / 人工 ${news.tpl.human} | 自动 ${ticket.tpl.auto} / 人工 ${ticket.tpl.human} |`);
console.log(`| 三问结构 | 单问 choice(利好/利空/中性) | 部门 choice + 紧急度 score + 转人工 noul |`);
console.log(`| 输出消费方 | 投资者资讯推送(摘要/提醒) | 坐席分发/自动回复/升级人工 |`);
console.log(`| 误判代价 | 低(展示排序) | 高(升级/回复直达客户) |`);
