#!/usr/bin/env node
// 迁移正确性机器验证:同区间分别用 JSONL 与 DB 聚合 (date,task,level)→count,对比一致
// 用法: node verify-db.mjs [logs目录,默认 ./logs]
import fs from "node:fs";
import path from "node:path";
import { openDb, levelOfUnified, localDate } from "./db.mjs";

const LOG_DIR = process.argv[2] || path.join(import.meta.dirname, "logs");
const db = openDb();

// JSONL 侧聚合(含 .imported;未迁移的 .jsonl 也纳入)
const fromFiles = new Map(); // date|task|level -> {count, latSum}
for (const f of fs.readdirSync(LOG_DIR).filter((f) => /^decisions-\d{6}\.jsonl(\.imported)?$/.test(f)).sort()) {
  for (const line of fs.readFileSync(path.join(LOG_DIR, f), "utf8").split("\n")) {
    if (!line.trim()) continue;
    let e;
    try { e = JSON.parse(line); } catch { continue; }
    const answers = e.answers ?? e.decoded;
    if (!answers || e.type === "llm") continue;
    const { level } = levelOfUnified(answers);
    const k = `${localDate(e.ts)}|${e.task}|${level}`;
    const b = fromFiles.get(k) ?? { count: 0, latSum: 0 };
    b.count++; b.latSum += e.latencyMs || 0;
    fromFiles.set(k, b);
  }
}

// DB 侧聚合
const fromDb = new Map();
for (const r of db.prepare("SELECT date,task,level,COUNT(*) count,SUM(latency_ms) latSum FROM decisions WHERE type='decision' GROUP BY date,task,level").all()) {
  fromDb.set(`${r.date}|${r.task}|${r.level}`, { count: Number(r.count), latSum: Number(r.latSum) });
}

// 对比
let bad = 0;
const keys = new Set([...fromFiles.keys(), ...fromDb.keys()]);
for (const k of [...keys].sort()) {
  const f = fromFiles.get(k), d = fromDb.get(k);
  const fc = f?.count ?? 0, dc = d?.count ?? 0;
  if (fc !== dc) { bad++; console.log(`✗ ${k}: JSONL=${fc} DB=${dc}`); }
  else if (fc) console.log(`✓ ${k}: ${fc} 条, avgLatency ${Math.round((f?.latSum||0)/fc)} vs ${Math.round((d?.latSum||0)/dc)}`);
}
const dbLlm = db.prepare("SELECT COUNT(*) c FROM decisions WHERE type='llm'").get().c;
console.log(`\n${bad ? `不一致 ${bad} 组` : "全部一致"} (llm 行 DB=${dbLlm})`);
db.close();
process.exit(bad ? 1 : 0);
