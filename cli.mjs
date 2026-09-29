#!/usr/bin/env node
// laya — System-1 决策模型 CLI(原生 onnxruntime 常驻 runner)
//
// 用法:
//   laya infer            从 stdin 读 {state, questions},输出 {answers, usage, latencyMs}
//   laya serve [--port N] HTTP 常驻服务,POST /system-one,GET /health(默认 8787)
//   laya status           daemon 存活检查
//   laya stop             停掉常驻 daemon
//
// 示例:
//   echo '{"state":{...},"questions":{...}}' | laya infer
//   curl -s localhost:8787/system-one -d '{"state":...,"questions":...}'
import { LayaNative } from "./laya-native.mjs";
import http from "node:http";
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
// LAYA_MODEL=en|multi (默认 en;multi = mmBERT 多语言 checkpoint,中文/德文更强)
const MODEL = process.env.LAYA_MODEL === "en" ? "en" : "multi"; // 默认 multi(中文/多语言更强)
const SOCKET = path.join(HERE, `laya-${MODEL}.sock`);
const MODEL_DIR = MODEL === "multi" ? path.join(HERE, "laya-multilingual-int8") : path.join(HERE, "laya-onnx-int8");
const MODEL_PATH = MODEL === "multi" ? "/sdcard/models/laya-multilingual-int8/laya-multilingual.int8.onnx" : "/sdcard/models/laya-onnx-int8/model.onnx";
const RUNNER = path.join(HERE, "runner");
const THREADS = 6;
const ORTDIR = "/data/data/com.termux/files/usr/lib/python3.12/site-packages/onnxruntime/capi";

function daemonEnv() {
  return { ...process.env, LD_LIBRARY_PATH: `${ORTDIR}:${path.join(HERE, "pylib")}:${process.env.PREFIX ?? "/data/data/com.termux/files/usr"}/lib` };
}

// 缓存客户端:每请求重建连接+tokenizer 会撑爆堆(实测 2GB OOM)
let _native = null, _nativePromise = null;
async function getNative() {
  if (_native) return _native;
  if (!_nativePromise) {
    _nativePromise = LayaNative
      .loadWithDaemon({ modelDir: MODEL_DIR, modelPath: MODEL_PATH, socketPath: SOCKET, threads: THREADS, runnerBin: RUNNER, env: daemonEnv() })
      .then((n) => { _native = n; _nativePromise = null; return n; })
      .catch((e) => { _nativePromise = null; throw e; });
  }
  return _nativePromise;
}

async function inferSafe(state, questions) {
  for (let i = 0; i < 2; i++) {
    try {
      const laya = await getNative();
      return await laya.systemOne(state, questions);
    } catch (e) {
      if (_native) { try { _native.sock.destroy(); } catch {} _native = null; }
      if (i === 1) throw e;
    }
  }
}

async function readStdinJson() {
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  const raw = Buffer.concat(chunks).toString("utf8").trim();
  if (!raw) throw new Error("stdin 为空:需要 JSON {state, questions}");
  const obj = JSON.parse(raw);
  if (!obj || typeof obj !== "object" || !obj.state || !obj.questions) throw new Error('JSON 需要 {"state": ..., "questions": ...}');
  return obj;
}

async function cmdInfer() {
  const input = await readStdinJson();
  const laya = await getNative();
  const r = await laya.systemOne(input.state, input.questions);
  const { _inferMs, ...rest } = r;
  console.log(JSON.stringify({ ...rest, latencyMs: Math.round(_inferMs) }, null, 2));
}

function cmdServe(port) {
  const server = http.createServer(async (req, res) => {
    const json = (code, obj) => {
      const body = JSON.stringify(obj, null, 2);
      res.writeHead(code, { "content-type": "application/json" });
      res.end(body);
    };
    if (req.method === "GET" && req.url === "/health") {
      const alive = await LayaNative.ping(SOCKET);
      return json(200, { ok: true, daemon: alive, socket: SOCKET });
    }
    if (req.method === "POST" && (req.url === "/system-one" || req.url === "/")) {
      const chunks = [];
      let size = 0;
      for await (const c of req) {
        size += c.length;
        if (size > 2 * 1024 * 1024) { res.writeHead(413); return res.end('{"error":"body too large"}'); }
        chunks.push(c);
      }
      try {
        const obj = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        if (!obj?.state || !obj?.questions) return json(400, { error: '需要 {"state":..., "questions":...}' });
        const r = await inferSafe(obj.state, obj.questions);
        return json(200, { answers: r.answers, usage: r.usage, latencyMs: Math.round(r._inferMs) });
      } catch (e) {
        return json(500, { error: String(e.message ?? e) });
      }
    }
    json(404, { error: "not found", endpoints: ["GET /health", "POST /system-one"] });
  });
  server.listen(port, "127.0.0.1", () => {
    console.log(`laya serve [${MODEL}]: http://127.0.0.1:${port}/system-one (POST {state,questions})`);
    console.log(`daemon socket: ${SOCKET}`);
    getNative()
      .then(() => console.log("daemon prewarmed"))
      .catch((e) => console.error("daemon prewarm failed:", e.message ?? e));
  });
}

async function main() {
  const [cmd, ...args] = process.argv.slice(2);
  switch (cmd ?? "infer") {
    case "infer":
      await cmdInfer();
      break;
    case "serve": {
      const i = args.indexOf("--port");
      const port = i >= 0 ? Number(args[i + 1]) : 8787;
      cmdServe(Number.isFinite(port) ? port : 8787);
      break;
    }
    case "status": {
      const alive = await LayaNative.ping(SOCKET);
      console.log(alive ? `daemon: alive (${SOCKET})` : "daemon: not running");
      process.exitCode = alive ? 0 : 1;
      break;
    }
    case "stop": {
      const { execSync } = await import("node:child_process");
      // 1) graceful exit for the socket owner; 2) SIGKILL any leftover runners via /proc scan
      //    (Termux pkill/pgrep fail to match these processes, so scan /proc directly)
      try {
        if (await LayaNative.ping(SOCKET)) {
          const laya = await LayaNative.load({ modelDir: MODEL_DIR, socketPath: SOCKET });
          await laya.shutdown();
        }
      } catch {}
      await new Promise((r) => setTimeout(r, 300));
      let killed = 0;
      for (const pid of fs.readdirSync("/proc").filter((p) => /^\d+$/.test(p))) {
        try {
          const cmd = fs.readFileSync(`/proc/${pid}/cmdline`, "utf8");
          if (cmd.startsWith(`${RUNNER}\u0000`) || cmd === RUNNER) {
            process.kill(Number(pid), "SIGKILL");
            killed++;
          }
        } catch {}
      }
      await new Promise((r) => setTimeout(r, 500));
      const alive = await LayaNative.ping(SOCKET);
      console.log(alive ? "daemon: still alive?!" : `daemon: stopped (${killed} process${killed === 1 ? "" : "es"} killed)`);
      process.exitCode = alive ? 1 : 0;
      break;
    }
    default:
      console.log("用法: laya infer | serve [--port N] | status | stop");
      process.exitCode = 2;
  }
}

main().catch((e) => {
  console.error("laya:", e.message ?? e);
  process.exit(1);
});
