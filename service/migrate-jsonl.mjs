#!/usr/bin/env node
// 存量决策日志迁移:logs/decisions-*.jsonl → laya.db decisions 表(幂等,可重跑)
// 逐文件记 meta['migrated:<file>'];完成后 JSONL 改名 .imported(不删除)。
// ⚠ level 按统一口径(db.mjs levelOfUnified:noul>=0.5→高)重算,与旧 Kotlin 口径(noul>=0.5→低)相反的历史行,报表"高/低"分布会移动。
import fs from "node:fs";
import path from "node:path";
import { openDb, insertDecision, metaGet, metaSet, tx } from "./db.mjs";

const LOG_DIR = path.join(import.meta.dirname, "logs");
const DRY_RUN = process.argv.includes("--dry");

const db = openDb();
if (!fs.existsSync(LOG_DIR)) { console.log("无 logs 目录,无需迁移"); process.exit(0); }
const files = fs.readdirSync(LOG_DIR).filter((f) => /^decisions-\d{6}\.jsonl$/.test(f)).sort();
if (!files.length) { console.log("无 decisions-*.jsonl,无需迁移"); process.exit(0); }

let total = 0, inserted = 0, skippedLine = 0;
for (const f of files) {
  if (metaGet(db, `migrated:${f}`)) { console.log(`跳过(已迁移): ${f}`); continue; }
  const lines = fs.readFileSync(path.join(LOG_DIR, f), "utf8").split("\n").filter((l) => l.trim());
  let n = 0;
  if (!DRY_RUN) {
    tx(db, () => {
      for (const line of lines) {
        let e;
        try { e = JSON.parse(line); } catch { skippedLine++; continue; }
        // 两侧字段兼容:Node answers / APK decoded;llm 升级行映射 type='llm'
        const answers = e.answers ?? e.decoded;
        if (e.type === "llm") {
          inserted += insertDecision(db, { ts: e.ts, task: e.task, type: "llm", llm: JSON.stringify(e.llm ?? { error: e.error ?? "" }), latencyMs: e.latencyMs, via: e.via || "" });
        } else if (answers) {
          inserted += insertDecision(db, { ts: e.ts, task: e.task, state: e.state, answers, latencyMs: e.latencyMs, via: e.via || "" });
        } else { skippedLine++; continue; }
        n++;
      }
    });
  } else n = lines.length;
  total += lines.length;
  console.log(`${DRY_RUN ? "[dry] " : ""}${f}: ${lines.length} 行 → 插入 ${n}`);
  if (!DRY_RUN) metaSet(db, `migrated:${f}`, String(n));
}

if (!DRY_RUN) {
  const dbCount = db.prepare("SELECT COUNT(*) c FROM decisions").get().c;
  console.log(`\n迁移完成: JSONL 总行 ${total}, 插入 ${inserted}, 坏行跳过 ${skippedLine}; DB decisions 现有 ${dbCount} 行`);
  for (const f of files) {
    const to = path.join(LOG_DIR, f.replace(/\.jsonl$/, ".jsonl.imported"));
    fs.renameSync(path.join(LOG_DIR, f), to);
    console.log(`改名: ${f} → ${path.basename(to)}`);
  }
}
db.close();
