#!/usr/bin/env node
// 网关层:邮件(IMAP 拉取/SMTP 回复)+ 消息队列(MQTT 订阅/发布)
// 由 server.mjs 在同进程内启动;配置缺失时静默降级。
// 配置: service/config/email.json / service/config/mq.json
// 邮件流程:轮询收件箱 → 主题或正文匹配 "laya <业务id> <文本>" → 决策 → SMTP 回复结果
// MQ 流程:订阅 sub 主题 {"id","task","text"} → 决策 → 发布 pub {"id","task","answers","latencyMs"}
import fs from "node:fs";
import path from "node:path";
import os from "node:os";

const HERE = os.homedir() + "/laya-test/service";
const CFG_DIR = path.join(HERE, "config");
fs.mkdirSync(CFG_DIR, { recursive: true });

const EMAIL_CFG = path.join(CFG_DIR, "email.json");
const MQ_CFG = path.join(CFG_DIR, "mq.json");

export const emailConfigured = () => fs.existsSync(EMAIL_CFG);
export const mqConfigured = () => fs.existsSync(MQ_CFG);

export function readEmailConfig() {
  try { return JSON.parse(fs.readFileSync(EMAIL_CFG, "utf8")); } catch { return null; }
}
export function readMqConfig() {
  try { return JSON.parse(fs.readFileSync(MQ_CFG, "utf8")); } catch { return null; }
}
export function writeEmailConfig(cfg) {
  fs.writeFileSync(EMAIL_CFG, JSON.stringify(cfg, null, 2));
}
export function writeMqConfig(cfg) {
  fs.writeFileSync(MQ_CFG, JSON.stringify(cfg, null, 2));
}

/** 决策回调由 server 注入:(task, text) -> {answers, latencyMs} */
let decide = null;
export function setDecideFn(fn) { decide = fn; }

const log = (...a) => console.log("[gateway]", ...a);

// ---------------- 邮件网关 ----------------
let emailTimer = null;

export async function startEmailGateway() {
  const cfg = readEmailConfig();
  if (!cfg || cfg.enabled === false) { log("邮件网关:未配置或禁用,跳过"); return false; }
  if (!decide) throw new Error("decide 函数未注入");
  const { poll = {} } = cfg;
  const intervalMs = (poll.intervalSec ?? 60) * 1000;
  await emailPollOnce(cfg).catch((e) => log("邮件轮询首轮失败:", e.message));
  emailTimer = setInterval(() => emailPollOnce(cfg).catch((e) => log("邮件轮询失败:", e.message)), intervalMs);
  log(`邮件网关已启动(每 ${poll.intervalSec ?? 60}s 轮询 ${cfg.imap?.host})`);
  return true;
}
export function stopEmailGateway() {
  if (emailTimer) { clearInterval(emailTimer); emailTimer = null; log("邮件网关已停止"); }
}

async function emailPollOnce(cfg) {
  const { ImapFlow } = await import("imapflow");
  const client = new ImapFlow({ host: cfg.imap.host, port: cfg.imap.port ?? 993, secure: cfg.imap.secure !== false, auth: { user: cfg.imap.user, pass: cfg.imap.pass }, logger: false });
  await client.connect();
  const lock = await client.getMailboxLock(cfg.imap.mailbox ?? "INBOX");
  const replied = [];
  try {
    // 搜索未读
    for await (const msg of client.fetch({ seen: false }, { envelope: true, source: true, uid: true })) {
      const subject = msg.envelope?.subject || "";
      const body = msg.source.toString("utf8");
      const cmd = parseCommand(subject) || parseCommand(body);
      if (!cmd) { await client.messageFlagsAdd(msg.uid, ["\\Seen"], { uid: true }); continue; }
      let replyText;
      try {
        const r = await decide(cmd.task, cmd.text);
        replyText = formatDecision(cmd.task, cmd.text, r);
      } catch (e) {
        replyText = `决策失败: ${e.message}`;
      }
      await sendMail(cfg.smtp, msg.envelope?.from?.[0]?.address, `Re: ${subject} | Laya 决策结果`, replyText);
      await client.messageFlagsAdd(msg.uid, ["\\Seen"], { uid: true });
      replied.push({ from: msg.envelope?.from?.[0]?.address, subject });
      log(`邮件决策完成 → ${msg.envelope?.from?.[0]?.address} (${cmd.task})`);
    }
  } finally {
    lock.release();
    await client.logout().catch(() => {});
  }
  return replied;
}

/** 从文本解析 "laya <task> <text...>"(首行) */
function parseCommand(text) {
  if (!text) return null;
  for (const line of text.split(/\r?\n/)) {
    const m = line.trim().match(/^laya\s+([a-z0-9_]+)\s+([\s\S]+)$/i);
    if (m) return { task: m[1].toLowerCase(), text: m[2].trim() };
  }
  return null;
}

function formatDecision(task, text, r) {
  const L = [`Laya 决策结果(业务: ${task})`, "", `输入: ${text.slice(0, 100)}`, ""];
  for (const [qid, a] of Object.entries(r.answers || {})) {
    if (a.type === "choice") {
      L.push(`• ${a.choice}`);
      const probs = Object.entries(a.probabilities || {}).map(([k, v]) => `${k} ${(v * 100).toFixed(1)}%`).join(" | ");
      if (probs) L.push(`  ${probs}`);
    } else if (a.type === "score") L.push(`• 评分: ${Number(a.score).toFixed(2)}/5`);
    else if (a.type === "noul") L.push(`• 判定: ${a.noul >= 0.5 ? "是" : "否"}(${Number(a.noul).toFixed(2)})`);
  }
  L.push("", `耗时 ${r.latencyMs}ms`);
  return L.join("\n");
}

async function sendMail(smtp, to, subject, text) {
  if (!to) return;
  const nodemailer = await import("nodemailer");
  const transport = nodemailer.createTransport({ host: smtp.host, port: smtp.port ?? 465, secure: smtp.secure !== false, auth: { user: smtp.user, pass: smtp.pass } });
  await transport.sendMail({ from: smtp.user, to, subject, text });
}

export async function emailTestSend(to, text) {
  const cfg = readEmailConfig();
  if (!cfg?.smtp) throw new Error("SMTP 未配置");
  await sendMail(cfg.smtp, to, "Laya 决策服务测试邮件", text || "测试通过 ✅");
}

// ---------------- MQTT 网关 ----------------
let mqClient = null;

export async function startMqGateway() {
  const cfg = readMqConfig();
  if (!cfg || cfg.enabled === false) { log("MQTT 网关:未配置或禁用,跳过"); return false; }
  if (!decide) throw new Error("decide 函数未注入");
  const mqtt = await import("mqtt");
  mqClient = mqtt.connect(cfg.mqtt?.url, { username: cfg.mqtt?.username, password: cfg.mqtt?.password, clientId: cfg.mqtt?.clientId || "laya-svc-" + Date.now() });
  const sub = cfg.topics?.sub ?? "laya/req/+";
  const pub = cfg.topics?.pub ?? "laya/resp";
  mqClient.on("connect", () => { log(`MQTT 已连接 ${cfg.mqtt?.url},订阅 ${sub}`); mqClient.subscribe(sub); });
  mqClient.on("message", async (topic, payload) => {
    try {
      const msg = JSON.parse(payload.toString("utf8"));
      const r = await decide(msg.task, String(msg.text ?? ""));
      const outTopic = cfg.topics?.perTaskPub ? `${pub}/${msg.task}` : pub;
      mqClient.publish(outTopic, JSON.stringify({ id: msg.id, task: msg.task, answers: r.answers, latencyMs: r.latencyMs }));
      log(`MQ 决策完成 ${msg.task} → ${outTopic}`);
    } catch (e) { log("MQ 消息处理失败:", e.message); }
  });
  mqClient.on("error", (e) => log("MQTT 错误:", e.message));
  return true;
}
export function stopMqGateway() {
  if (mqClient) { try { mqClient.end(true); } catch {} mqClient = null; log("MQTT 网关已停止"); }
}
