#!/usr/bin/env node
// 全业务网关自动数据采集 + 决策测试:
// 本地 aedes broker + MQTT 网关(cs 主题)in-process,7 业务样本逐条发布 laya/cs/req {"id","task","text"},
// 收集 laya/cs/resp 决策,汇总每业务延迟/判定/模板结构。决策后端走 --api(默认 APK 8790 /decide)。
// 用法: node service/collect-biz.mjs [--api http://127.0.0.1:8790] [--port 18830] [--per 3] [--clear]
//   --clear  采集前清空 Termux 决策日志(SQLite laya.db decisions 表)
import { Aedes } from "aedes";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const mqtt = require("mqtt");
const HERE = path.dirname(new URL(import.meta.url).pathname);

const args = process.argv.slice(2);
const opt = (k, d) => {
  const i = args.indexOf("--" + k);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith("--") ? args[i + 1] : d;
};
const API = opt("api", "http://127.0.0.1:8790").replace(/\/$/, "");
const PORT = parseInt(opt("port", "18830"), 10);
const PER = parseInt(opt("per", "3"), 10);
const CLEAR = args.includes("--clear");
const URL_ = `mqtt://127.0.0.1:${PORT}`;
const log = (...a) => console.log("[collect]", ...a);

// ---- 每业务样本数据(自动采集集) ----
const SAMPLES = {
  multi: [ // 多语言工单分流(基础),三问同 ticket,中英混合
    "Mein Laptop lässt sich nach dem Software-Update nicht mehr einschalten, bitte dringend um Hilfe.",
    "The invoice shows a double charge for last month's subscription, I request a refund.",
    "我的手机屏幕碎裂,需要寄修流程说明。",
  ],
  ticket: [ // 客服工单分流(en,匹配训练分布)
    "Our projectors stopped syncing after the firmware update; the field team cannot present today.",
    "I was billed twice for the same cloud storage plan this month, please correct the invoice.",
    "We want to return 12 unopened projector lamps ordered last week.",
  ],
  ugc: [ // UGC 内容审核
    "这篇文章讲得很好,学到了很多投影仪的保养知识,感谢分享。",
    "加微信 xxx888 领取免费福利,限时推广,名额有限速来!",
    "你就是个骗子,全家都是骗子,垃圾东西别再出现了。",
  ],
  agent: [ // Agent 工作流路由
    "今天上海天气怎么样?",
    "帮我查一下公司知识库里关于报销流程的问答。",
    "对比三款投影仪的价格、亮度和售后政策,生成选型报告并预约采购流程。",
  ],
  risk: [ // 金融风控前置
    "用户申请提升信用卡额度至50万,声称月收入80万,要求立即审批。",
    "老客户正常消费分期,还款记录良好,申请将剩余账单转为12期。",
    "新注册账户当日跨境转账6笔共80万元,收款方均为境外个人账户。",
  ],
  news: [ // 财经资讯评分(中文快讯)
    "某公司公告:获得国家科技进步一等奖,并中标15亿元智慧城市大单。",
    "某公司三季报:净利润同比下滑47%,主要产品价格持续走低。",
    "某公司公告:今日股票停牌一天,将于明日披露相关事项。",
  ],
  news2: [ // 财经资讯评分2(教师中译语料)
    "美联储宣布维持基准利率不变,市场预计降息周期将延后至明年年中。",
    "某芯片公司遭主要客户大幅砍单,股价盘前下跌9%。",
    "某公司公告:与政府签订新的基础设施合作协议,细节将于下周公布。",
  ],
};
const TASKS = Object.keys(SAMPLES);

// ---- 决策注入:POST /decide {"task","text"} ----
async function decide(task, text) {
  const r = await fetch(`${API}/decide`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ task, text }),
  });
  if (!r.ok) throw new Error(`HTTP ${r.status}: ${(await r.text()).slice(0, 100)}`);
  const j = await r.json();
  return { answers: j.answers, latencyMs: j.latencyMs, reply: j.reply };
}

// ---- 清旧数据(Termux 决策日志,SQLite) ----
if (CLEAR) {
  const { openDb } = await import("./db.mjs");
  const db = openDb();
  const n = Number(db.prepare("DELETE FROM decisions").run().changes);
  db.close();
  log(`已清空 Termux 决策日志(DB decisions ${n} 行)`);
}

// ---- 主流程(broker + 网关 + 发布/收集) ----
const MQ_CFG = path.join(HERE, "config", "mq.json");
const backup = fs.existsSync(MQ_CFG) ? fs.readFileSync(MQ_CFG, "utf8") : null;
const results = new Map(); // id -> resp
let pending = 0, allDone;

async function main() {
  const aedes = await Aedes.createBroker();
  const broker = net.createServer(aedes.handle);
  await new Promise((ok) => broker.listen(PORT, "127.0.0.1", ok));

  fs.mkdirSync(path.dirname(MQ_CFG), { recursive: true });
  fs.writeFileSync(MQ_CFG, JSON.stringify({
    enabled: true,
    mqtt: { url: URL_, clientId: "laya-collect" },
    topics: { sub: "laya/test/req/+", pub: "laya/test/resp", csSub: "laya/cs/req", csPub: "laya/cs/resp" },
  }, null, 2));
  const gw = await import("./gateways.mjs");
  gw.setDecideFn(decide);
  await gw.startMqGateway();
  log(`网关已启动(cs 主题 → ${API}),${TASKS.length} 业务 × ${PER} 样本`);

  const client = mqtt.connect(URL_, { clientId: "collector-" + Date.now() });
  await new Promise((ok, err) => { client.once("connect", ok); client.once("error", err); });
  allDone = new Promise((ok) => {
    client.subscribe("laya/cs/resp", () => {
      client.on("message", (_t, payload) => {
        try {
          const m = JSON.parse(payload.toString("utf8"));
          results.set(m.id, m);
          if (--pending <= 0) ok();
        } catch {}
      });
    });
  });

  const jobs = []; // [id, task, text]
  for (const task of TASKS) {
    for (let i = 0; i < Math.min(PER, SAMPLES[task].length); i++) jobs.push([`${task}-${i}`, task, SAMPLES[task][i]]);
  }
  pending = jobs.length;
  for (const [id, task, text] of jobs) {
    client.publish("laya/cs/req", JSON.stringify({ id, task, text }));
  }
  const timer = new Promise((ok) => setTimeout(ok, 300_000));
  await Promise.race([allDone, timer]);
  if (results.size < jobs.length) log(`超时:收到 ${results.size}/${jobs.length}`);

  // ---- 汇总 ----
  const lat = (xs) => xs.slice().sort((a, b) => a - b);
  console.log(`\n## 全业务网关采集决策汇总(${results.size}/${jobs.length},后端 ${API})\n`);
  console.log("| 业务 | 收到 | 平均延迟 | 判定(q0) | 模板 |");
  console.log("|---|---|---|---|---|");
  for (const task of TASKS) {
    const rs = [...results.entries()].filter(([id]) => id.startsWith(task + "-")).map(([, v]) => v);
    if (!rs.length) { console.log(`| ${task} | 0 | - | - | - |`); continue; }
    const ls = lat(rs.map((r) => r.latencyMs || 0));
    const main = (r) => {
      const q0 = (r.answers || {}).q0 || Object.values(r.answers || {})[0] || {};
      return q0.choice ?? (q0.score != null ? Math.trunc(q0.score) + "分" : "?");
    };
    const ds = [...new Set(rs.map(main))].join("/");
    const tpl = [...new Set(rs.map((r) => ((r.reply || "").match(/【[^】]+】/g) || ["(无)"]).join("+")))].join(" / ");
    console.log(`| ${task} | ${rs.length}/${Math.min(PER, SAMPLES[task].length)} | ${ls.reduce((a, b) => a + b, 0) / ls.length | 0}ms | ${ds} | ${tpl} |`);
  }
}

try {
  await main();
} catch (e) {
  console.error("[collect] 失败:", e.message);
  process.exitCode = 1;
} finally {
  try { const gw = await import("./gateways.mjs"); gw.stopMqGateway(); } catch {}
  if (backup != null) fs.writeFileSync(MQ_CFG, backup); else fs.rmSync(MQ_CFG, { force: true });
  log("mq.json 已恢复");
  setTimeout(() => process.exit(process.exitCode || 0), 200).unref();
}
