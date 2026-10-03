#!/usr/bin/env node
// LiteRT GPU 任务模型 parity:按任务拉起 litert-runner(adb shell 域),跑 test 集对拍
// 用法: node parity-litert.mjs <ticket|ugc|agent|risk> [N=100] [port=7891]
import { LayaNative } from "./laya-native.mjs";
import { execFileSync } from "node:child_process";
import net from "node:net";
import fs from "fs";
import os from "os";

const HOME = os.homedir();
const task = process.argv[2] || "ticket";
const N = parseInt(process.argv[3] || "100", 10);
const PORT = parseInt(process.argv[4] || "7891", 10);
const E = (m) => process.stderr.write(`[litert:${task}] ` + m + "\n");

const RUNNER_DIR = "/data/local/tmp/litert";
const modelDir = `/sdcard/models/laya-litert-${task}`;
const MAIN = process.argv[5] || `${modelDir}/laya_${task}_s256_embeds.tflite`;
const ACT = `${modelDir}/laya_${task}_act_head_fp32.tflite`;
const EMBED = `${modelDir}/token_embeddings_fp16.bin`;
const CACHE = `/data/local/tmp/gpucache-${task}`;
const LD = `${RUNNER_DIR}:/system/lib64`;

function adbSerial() {
  const out = execFileSync("adb", ["devices"], { encoding: "utf8" });
  const line = out.split("\n").find((l) => l.includes("\tdevice"));
  if (!line) throw new Error("no adb device (开无线调试后跑 ~/adbre.sh)");
  return line.split("\t")[0];
}
function adbShell(serial, cmd, { bg = false } = {}) {
  const c = bg ? `nohup sh -c '${cmd}' >/data/local/tmp/litert/runner-${task}.log 2>&1 &` : cmd;
  return execFileSync("adb", ["-s", serial, "shell", c], { encoding: "utf8", timeout: 30000 });
}
function tcpProbe(timeoutMs = 1000) {
  return new Promise((resolve) => {
    const sock = net.createConnection({ port: PORT, host: "127.0.0.1" });
    const done = (v) => { clearTimeout(t); sock.destroy(); resolve(v); };
    const t = setTimeout(() => done(false), timeoutMs);
    sock.once("connect", () => done(true));
    sock.once("error", () => done(false));
  });
}

const serial = adbSerial();
E(`serial=${serial}`);
// 先杀同任务旧 runner(按 socket 参数匹配)
try { adbShell(serial, `pkill -f "litert-runner.*${PORT}"`); } catch {}
adbShell(serial, `mkdir -p ${CACHE} /data/local/tmp/litert`);
const sockArg = "tcp:" + PORT;
const cmd = `LD_LIBRARY_PATH=${LD} ${RUNNER_DIR}/litert-runner ${MAIN} ${ACT} ${EMBED} ${sockArg} ${CACHE}`;
E("spawn: " + cmd);
adbShell(serial, cmd, { bg: true });

let up = false;
for (let i = 0; i < 300; i++) {
  if (await tcpProbe()) { up = true; break; }
  await new Promise((r) => setTimeout(r, 200));
}
if (!up) {
  const log = adbShell(serial, `tail -20 /data/local/tmp/litert/runner-${task}.log 2>/dev/null`).trim();
  throw new Error(`litert daemon(${task}) 60s 未就绪\n--- runner.log ---\n${log}`);
}
E("GPU daemon up, loading tokenizer/config...");
const client = await LayaNative.load({ modelDir, socketPath: { port: PORT, host: "127.0.0.1" } });

try {
  const cfg = client.config;
  const stateKey = Object.keys(cfg.state_format || { sms: 1 })[0];
  const qdefs = cfg.question_defs;
  const questions = Object.fromEntries(qdefs.map((q) => [q.qid, { type: q.type, instructions: q.instructions, criteria: q.criteria }]));
  const classes = cfg.classes || [];
  const rows = fs.readFileSync(`${HOME}/laya-test/finetune/data/${task}-test.jsonl`, "utf8").trim().split("\n").map(JSON.parse).slice(0, N);
  const taskDef = JSON.parse(fs.readFileSync(`${HOME}/laya-test/finetune/task_${task}.json`, "utf8"));
  const fromMap = Object.fromEntries(taskDef.questions.map((t) => [t.qid, t.from]));
  E(`rows=${rows.length}, warming up...`);
  for (const r of rows.slice(0, 2)) await client.systemOne({ [stateKey]: r.text }, questions);

  const cm = new Map();
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
      const gold = row[fromMap[q.qid]];
      if (pred === undefined || gold === undefined || gold === null) continue;
      if (q.type === "choice") {
        const k = gold + "\t" + pred.choice;
        cm.set(k, (cm.get(k) || 0) + 1);
      } else if (q.type === "score") {
        scoreErr.push(Math.abs(parseFloat(pred.score) - parseFloat(gold)));
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
  const prf = (tp, fp, fn) => ({ p: tp + fp ? tp / (tp + fp) : 0, r: tp + fn ? tp / (tp + fn) : 0 });
  const out = { model: `laya-${task}-litert-gpu(手机)`, n };
  if (cm.size) {
    let correct = 0;
    for (const [k, v] of cm) if (k.split("\t")[0] === k.split("\t")[1]) correct += v;
    const per = {};
    const f1s = [];
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
    out.choice = { acc: +(correct / n).toFixed(4), macro_f1: +(f1s.reduce((a, b) => a + b, 0) / f1s.length).toFixed(4), per_class: per };
  }
  if (scoreErr.length) {
    out.score = { mae: +(scoreErr.reduce((a, b) => a + b, 0) / scoreErr.length).toFixed(3), within_1: +(scoreErr.filter((e) => e <= 1.0).length / scoreErr.length).toFixed(4) };
  }
  if (confs.length) {
    const { p, r } = prf(noulTP.tp, noulTP.fp, noulTP.fn);
    const f = p + r ? (2 * p * r) / (p + r) : 0;
    let ece = 0;
    for (let b = 0; b < 15; b++) {
      const lo = b / 15, hi = (b + 1) / 15;
      const idx = confs.map((c, i) => (c > lo && c <= hi ? i : -1)).filter((i) => i >= 0);
      if (idx.length) {
        const mc = idx.reduce((s, i) => s + confs[i], 0) / idx.length;
        const macc = idx.reduce((s, i) => s + corr[i], 0) / idx.length;
        ece += (idx.length / confs.length) * Math.abs(macc - mc);
      }
    }
    out.noul = { threshold: 0.5, precision: +p.toFixed(4), recall: +r.toFixed(4), f1: +f.toFixed(4), ece: +ece.toFixed(4) };
  }
  const sorted = [...lats].sort((a, b) => a - b);
  out.latency_ms = {
    p50: +sorted[Math.floor(sorted.length / 2)].toFixed(1),
    avg: +(lats.reduce((a, b) => a + b, 0) / lats.length).toFixed(1),
    p95: +sorted[Math.floor(sorted.length * 0.95)].toFixed(1),
  };
  fs.writeFileSync(`${HOME}/laya-test/finetune/phone-litert-metrics-${task}.json`, JSON.stringify(out, null, 2));
  console.log(JSON.stringify(out, null, 2));
} finally {
  try { adbShell(serial, `pkill -f "litert-runner.*${PORT}"`); } catch {}
  process.exit(0);
}
