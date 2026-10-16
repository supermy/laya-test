// Laya 统一存储层(node:sqlite,Node 26+ 自带,零依赖)
// 权威 DDL 在 schema.sql;APK 侧 Db.kt 以常量镜像,改动须两侧同步。
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const HERE = path.dirname(fileURLToPath(import.meta.url));
export const DB_PATH = path.join(HERE, "laya.db");
export const SCHEMA_VERSION = 1;

/** 打开数据库:WAL + 建表 + schema_version 校验 */
export function openDb(p = DB_PATH) {
  const db = new DatabaseSync(p);
  db.exec("PRAGMA journal_mode=WAL");
  db.exec("PRAGMA synchronous=NORMAL");
  db.exec(fs.readFileSync(path.join(HERE, "schema.sql"), "utf8"));
  const row = db.prepare("SELECT value FROM meta WHERE key='schema_version'").get();
  if (!row) db.prepare("INSERT INTO meta(key,value) VALUES('schema_version',?)").run(String(SCHEMA_VERSION));
  else if (parseInt(row.value, 10) > SCHEMA_VERSION) {
    db.close();
    throw new Error(`DB schema_version=${row.value} 高于代码 ${SCHEMA_VERSION},请升级程序`);
  }
  return db;
}

export function tx(db, fn) {
  db.exec("BEGIN");
  try { const r = fn(); db.exec("COMMIT"); return r; }
  catch (e) { db.exec("ROLLBACK"); throw e; }
}

export const metaGet = (db, key) => db.prepare("SELECT value FROM meta WHERE key=?").get(key)?.value ?? null;
export const metaSet = (db, key, value) => db.prepare("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").run(key, String(value));

// ---------- 等级口径(从 server.mjs 抽出,两侧唯一实现) ----------
// score 问:归一化分 ≥0.66 高 / ≥0.33 中 / 否则低;
// noul 问:noul>=0.5 为"命中"(需人工),等级高;纯 choice:判定标签本身。
export function levelOfUnified(answers) {
  const ans = answers || {};
  for (const a of Object.values(ans)) {
    if (a.type !== "score") continue;
    const max = a.legend ? Object.keys(a.legend).length - 1 : 4;
    const n = max > 0 ? (a.score ?? 0) / max : 0;
    const lab = a.legend?.[String(Math.round(a.score ?? 0))];
    const lv = n >= 0.66 ? "高" : n >= 0.33 ? "中" : "低";
    return { level: lv, basis: `score=${(a.score ?? 0).toFixed(2)}${lab ? `(${lab})` : ""}` };
  }
  for (const a of Object.values(ans)) {
    if (a.type === "noul") return { level: a.noul >= 0.5 ? "高" : "低", basis: `noul=${(a.noul ?? 0).toFixed(2)}${a.noul >= 0.5 ? "(命中)" : "(无信号)"}` };
  }
  for (const a of Object.values(ans)) {
    if (a.type === "choice") return { level: String(a.choice), basis: `choice=${a.choice}(conf=${(a.confidence ?? 0).toFixed(2)})` };
  }
  return { level: "未知", basis: "" };
}

export const localDate = (ts) => {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

const _insDecision = (db) => db.prepare(
  `INSERT OR IGNORE INTO decisions(ts,date,task,type,level,basis,state,answers,latency_ms,via,llm)
   VALUES(?,?,?,?,?,?,?,?,?,?,?)`
);

/** 写决策日志,返回 decisions.id。rec: {ts?,task,state,answers,latencyMs,via?,type?,llm?}(answers 可为对象或 JSON 串) */
export function insertDecision(db, rec) {
  const ts = rec.ts ?? Date.now();
  const answers = typeof rec.answers === "string" ? rec.answers : JSON.stringify(rec.answers || {});
  const ansObj = typeof rec.answers === "string" ? JSON.parse(rec.answers) : (rec.answers || {});
  const { level, basis } = levelOfUnified(ansObj);
  return Number(_insDecision(db).run(
    ts, localDate(ts), rec.task, rec.type || "decision", level, basis,
    rec.state || "", answers, rec.latencyMs || 0, rec.via || "", rec.llm ?? null,
  ).lastInsertRowid);
}

// ---------- 回复模板(templates 表只存用户覆盖行) ----------
export function listTemplates(db, { task, locale } = {}) {
  const conds = [], args = [];
  if (task) { conds.push("task=?"); args.push(task); }
  if (locale) { conds.push("locale=?"); args.push(locale); }
  return db.prepare(`SELECT rowid AS id,task,locale,kind,key,body,updated_at FROM templates${conds.length ? " WHERE " + conds.join(" AND ") : ""} ORDER BY task,locale,kind,key`).all(...args);
}

export function upsertTemplate(db, { task, locale, kind, key = "", body }) {
  if (!task || !locale || !kind || body == null) throw new Error("需要 task/locale/kind/body");
  return db.prepare(
    `INSERT INTO templates(task,locale,kind,key,body,updated_at) VALUES(?,?,?,?,?,?)
     ON CONFLICT(task,locale,kind,key) DO UPDATE SET body=excluded.body, updated_at=excluded.updated_at`
  ).run(task, locale, kind, key, String(body), Date.now()).changes;
}

export function deleteTemplate(db, id) {
  return db.prepare("DELETE FROM templates WHERE rowid=?").run(id).changes;
}

// ---------- 采集配置(collect_configs + meta['collect.intervalSec']) ----------
/** 等价读出旧 cfg.collect 结构 {intervalSec, items:{task:{enabled,samples[]}}} */
export function getCollect(db) {
  const intervalSec = parseInt(metaGet(db, "collect.intervalSec") ?? "0", 10) || 0;
  const items = {};
  for (const r of db.prepare("SELECT task,enabled,samples FROM collect_configs ORDER BY task").all()) {
    let samples = [];
    try { samples = JSON.parse(r.samples || "[]"); } catch {}
    items[r.task] = { enabled: !!r.enabled, samples };
  }
  return { intervalSec, items };
}

export function setCollect(db, { intervalSec, items = {} }) {
  tx(db, () => {
    if (intervalSec != null) metaSet(db, "collect.intervalSec", parseInt(intervalSec, 10) || 0);
    const now = Date.now();
    for (const [task, v] of Object.entries(items)) {
      db.prepare(
        `INSERT INTO collect_configs(task,enabled,samples,updated_at) VALUES(?,?,?,?)
         ON CONFLICT(task) DO UPDATE SET enabled=excluded.enabled, samples=excluded.samples, updated_at=excluded.updated_at`
      ).run(task, v.enabled ? 1 : 0, JSON.stringify(v.samples ?? []), now);
    }
  });
}

// ---------- 网关入站查询 ----------
export function listInbound(db, { from, to, channel, status, limit = 100 } = {}) {
  const conds = [], args = [];
  if (from) { conds.push("ts>=?"); args.push(new Date(from + "T00:00:00").getTime()); }
  if (to) { conds.push("ts<?"); args.push(new Date(to + "T00:00:00").getTime() + 86400e3); }
  if (channel) { conds.push("channel=?"); args.push(channel); }
  if (status) { conds.push("status=?"); args.push(status); }
  return db.prepare(
    `SELECT id,ts,channel,sender,subject,topic,task,decision_id,status,error,substr(text,1,200) text
     FROM inbound${conds.length ? " WHERE " + conds.join(" AND ") : ""} ORDER BY ts DESC LIMIT ?`
  ).all(...args, Math.min(1000, limit | 0 || 100));
}

// ---------- 网关入站留痕 ----------
const _insInbound = (db) => db.prepare(
  `INSERT INTO inbound(ts,channel,sender,subject,topic,text,task,status,error)
   VALUES(?,?,?,?,?,?,?,?,?)`
);

/** 落库一条入站请求,返回 inbound.id。rec: {channel,sender,subject?,topic?,text,task?,status?} */
export function insertInbound(db, rec) {
  const r = _insInbound(db).run(
    rec.ts ?? Date.now(), rec.channel, rec.sender || "", rec.subject || "",
    rec.topic || "", rec.text || "", rec.task || "", rec.status || "pending", "",
  );
  return Number(r.lastInsertRowid);
}

/** 决策完成后回填:status done(带 decision_id)| failed(带 error)| ignored */
export function linkInbound(db, id, { decisionId = null, status = "done", error = "" } = {}) {
  db.prepare("UPDATE inbound SET decision_id=?, status=?, error=? WHERE id=?")
    .run(decisionId, status, error, id);
}

/** 按条件取决策行,转为旧 JSONL entry 形状 {ts,task,state,answers,latencyMs,via}。
 *  默认只取 type='decision'(llm 升级行单查);date 参数为本地日期字符串闭开区间 [fromDate, toDate)。 */
export function queryDecisions(db, { fromDate, toDate, task, type = "decision", limit, orderDesc = false } = {}) {
  const conds = [], args = [];
  if (type) { conds.push("type=?"); args.push(type); }
  if (fromDate) { conds.push("date>=?"); args.push(fromDate); }
  if (toDate) { conds.push("date<?"); args.push(toDate); }
  if (task) { conds.push("task=?"); args.push(task); }
  const rows = db.prepare(
    `SELECT ts,task,type,state,answers,latency_ms,via FROM decisions${conds.length ? " WHERE " + conds.join(" AND ") : ""}
     ORDER BY ts ${orderDesc ? "DESC" : "ASC"}${limit ? " LIMIT " + (limit | 0) : ""}`
  ).all(...args);
  return rows.map((r) => ({
    ts: Number(r.ts), task: r.task, state: r.state,
    answers: JSON.parse(r.answers || "{}"), latencyMs: Number(r.latency_ms), via: r.via,
  }));
}

// ---------- 业务注册表 upsert ----------
const _upTask = (db) => db.prepare(
  `INSERT INTO tasks(id,label,backend,enabled,state_key,model_dir,model_path,question_defs,classes,normal_label,source,updated_at)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
   ON CONFLICT(id) DO UPDATE SET
     label=excluded.label, backend=excluded.backend, enabled=excluded.enabled,
     state_key=excluded.state_key, model_dir=excluded.model_dir, model_path=excluded.model_path,
     question_defs=excluded.question_defs, classes=excluded.classes, normal_label=excluded.normal_label,
     source=excluded.source, updated_at=excluded.updated_at`
);

/** scanModels() 结果 upsert 进 tasks 表 */
export function upsertTasks(db, tasks) {
  const now = Date.now();
  for (const t of Object.values(tasks)) {
    _upTask(db).run(
      t.id, t.label ?? t.id, t.backend ?? "cpu", t.enabled === false ? 0 : 1,
      t.stateKey ?? "text", t.modelDir ?? "", t.modelPath ?? "",
      JSON.stringify(t.questionDefs || []), t.classes ? JSON.stringify(t.classes) : null,
      t.normalLabel ?? null, t.source ?? "auto-scan", now,
    );
  }
}
