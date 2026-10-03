#!/usr/bin/env node
// 任务模型手机端 parity:ort-web 垫片跑 test 集,指标口径与 5060 evaluate.py 逐字对齐
//   score err = |pred.score(0-indexed 期望) - gold| ;noul p>=0.5;ECE 15 bins
// 用法: node parity-task.mjs <ticket|ugc|agent|risk> [N=150]
import { LayaNative } from "./laya-native.mjs";
import fs from "fs";
import path from "path";
import os from "os";

const task = process.argv[2] || "ticket";
const N = parseInt(process.argv[3] || "150", 10);
const E = (m) => process.stderr.write(`[parity:${task}] ` + m + "\n");
const HOME = os.homedir();

const modelDir = `/sdcard/models/laya-${task}-int8`;
const onnx = fs.readdirSync(modelDir).find((f) => f.endsWith(".int8.onnx"));
if (!onnx) throw new Error(`${modelDir} 里没有 int8 onnx`);
const dataPath = `${HOME}/laya-test/finetune/data/${task}-test.jsonl`;
const metricsPath = `${HOME}/laya-test/finetune/metrics-${task}.json`;
const RUNNER = `${HOME}/laya-test/runner`;
const ORTDIR = "/data/data/com.termux/files/usr/lib/python3.12/site-packages/onnxruntime/capi";

E(`load ${modelDir}/${onnx}`);
const client = await LayaNative.loadWithDaemon({
  modelDir,
  modelPath: `${modelDir}/${onnx}`,
  socketPath: `${HOME}/laya-test/laya-${task}-parity.sock`,
  threads: 6,
  runnerBin: RUNNER,
  env: { ...process.env, LD_LIBRARY_PATH: `${ORTDIR}:${path.join(HOME, "laya-test", "pylib")}:${process.env.PREFIX ?? "/data/data/com.termux/files/usr"}/lib` },
});
try {
  const cfg = client.config;
  const stateKey = Object.keys(cfg.state_format || { sms: 1 })[0];
  const qdefs = cfg.question_defs;
  if (!qdefs?.length) throw new Error("config.question_defs 缺失");
  const questions = Object.fromEntries(
    qdefs.map((q) => [q.qid, { type: q.type, instructions: q.instructions, criteria: q.criteria }]),
  );
  const classes = cfg.classes || [];
  const normal = cfg.normal_label;

  const rows = fs
    .readFileSync(dataPath, "utf8")
    .trim()
    .split("\n")
    .map((l) => JSON.parse(l))
    .slice(0, N);
  E(`rows=${rows.length} questions=${qdefs.map((q) => q.qid + ":" + q.type).join(",")}`);

  // warmup 2 条
  for (const r of rows.slice(0, 2)) await client.systemOne({ [stateKey]: r.text }, questions);

  const cm = new Map(); // "gold\tpred" -> n (choice)
  const scoreErr = [];
  const noulTP = { tp: 0, fp: 0, fn: 0 };
  const confs = [];
  const corr = [];
  const lats = [];
  let n = 0;

  for (const row of rows) {
    const t0 = performance.now();
    const res = await client.systemOne({ [stateKey]: row.text }, questions);
    lats.push(performance.now() - t0);
    n++;
    for (const q of qdefs) {
      const pred = res.answers[q.qid];
      const from =
        q.type === "choice" ? "label" : q.type === "score" ? (task === "risk" ? "risk_level" : q.qid === "q1" ? (task === "ticket" ? "urgency" : task === "ugc" ? "severity" : "complexity") : "") : q.qid === "q2" ? (task === "risk" || task === "ugc" ? "review" : task === "agent" ? "multi_step" : "escalate") : "";
      // 更稳:直接按 task_*.json 的 from 字段
      let goldKey = from;
      try {
        const taskDef = JSON.parse(fs.readFileSync(`${HOME}/laya-test/finetune/task_${task}.json`, "utf8"));
        goldKey = taskDef.questions.find((t) => t.qid === q.qid)?.from ?? "";
      } catch {}
      if (!goldKey) continue;
      const gold = row[goldKey];
      if (pred === undefined || gold === undefined || gold === null) continue;
      if (q.type === "choice") {
        const k = gold + "\t" + pred.choice;
        cm.set(k, (cm.get(k) || 0) + 1);
      } else if (q.type === "score") {
        const err = Math.abs(parseFloat(pred.score) - parseFloat(gold));
        scoreErr.push(err);
      } else {
        const g = parseInt(gold, 10);
        const p = parseFloat(pred.noul);
        const pred1 = p >= 0.5;
        if (pred1 && g === 1) noulTP.tp++;
        else if (pred1) noulTP.fp++;
        else if (g === 1) noulTP.fn++;
        confs.push(Math.max(p, 1 - p));
        corr.push(pred1 === (g === 1) ? 1 : 0);
      }
    }
  }

  const out = { model: `laya-${task}-int8(手机)`, n, rows: rows.length };
  const prf = (tp, fp, fn) => ({
    p: tp + fp ? tp / (tp + fp) : 0,
    r: tp + fn ? tp / (tp + fn) : 0,
  });
  if (cm.size) {
    let correct = 0;
    for (const [k, v] of cm) if (k.split("\t")[0] === k.split("\t")[1]) correct += v;
    const per = {};
    let f1s = [];
    for (const c of classes) {
      let tp = 0, fp = 0, fn = 0;
      for (const [k, v] of cm) {
        const [g, p] = k.split("\t");
        if (p === c && g !== c) fp += v;
        if (g === c && p !== c) fn += v;
        if (g === c && p === c) tp += v;
      }
      const { p, r } = prf(tp, fp, fn);
      const f = p + r ? (2 * p * r) / (p + r) : 0;
      per[c] = { precision: +p.toFixed(4), recall: +r.toFixed(4), f1: +f.toFixed(4) };
      f1s.push(f);
    }
    out.choice = {
      acc: +(correct / n).toFixed(4),
      macro_f1: +(f1s.reduce((a, b) => a + b, 0) / f1s.length).toFixed(4),
      per_class: per,
    };
  }
  if (scoreErr.length) {
    const mae = scoreErr.reduce((a, b) => a + b, 0) / scoreErr.length;
    out.score = {
      mae: +mae.toFixed(3),
      within_1: +(scoreErr.filter((e) => e <= 1.0).length / scoreErr.length).toFixed(4),
    };
  }
  if (confs.length) {
    const { p, r } = prf(noulTP.tp, noulTP.fp, noulTP.fn);
    const f = p + r ? (2 * p * r) / (p + r) : 0;
    let ece = 0;
    const bins = 15;
    for (let b = 0; b < bins; b++) {
      const lo = b / bins, hi = (b + 1) / bins;
      const idx = confs.map((c, i) => (c > lo && c <= hi ? i : -1)).filter((i) => i >= 0);
      if (idx.length) {
        const mc = idx.reduce((s, i) => s + confs[i], 0) / idx.length;
        const macc = idx.reduce((s, i) => s + corr[i], 0) / idx.length;
        ece += (idx.length / confs.length) * Math.abs(macc - mc);
      }
    }
    out.noul = {
      threshold: 0.5,
      precision: +p.toFixed(4),
      recall: +r.toFixed(4),
      f1: +f.toFixed(4),
      ece: +ece.toFixed(4),
    };
  }
  const sorted = [...lats].sort((a, b) => a - b);
  out.latency_ms = {
    p50: +sorted[Math.floor(sorted.length / 2)].toFixed(1),
    avg: +(lats.reduce((a, b) => a + b, 0) / lats.length).toFixed(1),
  };

  // 与 5060 checkpoint 指标对比
  let cmp = null;
  try {
    const ref = JSON.parse(fs.readFileSync(metricsPath, "utf8"));
    cmp = {};
    for (const k of ["choice", "score", "noul"]) {
      if (out[k] && ref[k]) {
        cmp[k] = Object.fromEntries(
          Object.keys(ref[k]).map((kk) => [kk, { ckpt: ref[k][kk], phone_int8: out[k][kk] }]),
        );
      }
    }
    cmp.latency_ms = { ckpt_5060_gpu: ref.latency_ms, phone_ort_cpu: out.latency_ms };
  } catch (e) {
    E("参考指标读取失败: " + e.message);
  }
  const result = { ...out, vs_5060_ckpt: cmp };
  fs.writeFileSync(`${HOME}/laya-test/finetune/phone-metrics-${task}.json`, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
} finally {
  // runner 是常驻 daemon,不等退出握手,直接强杀进程保证脚本退出
  const { execSync } = await import("node:child_process");
  const re = `^${RUNNER.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}.*${modelDir.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`;
  try { execSync(`pkill -f "${re}"`); } catch {}
  process.exit(0);
}
