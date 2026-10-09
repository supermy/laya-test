// LayaNative client for the LiteRT GPU runner (shell-domain daemon via adb).
// Reuses LayaNative (same wire protocol); only daemon lifecycle differs:
// the runner must run inside `adb shell` because libLiteRt needs system EGL libs.
import { LayaNative } from "./laya-native.mjs";
import { execFileSync, spawn } from "node:child_process";
import net from "node:net";

const RUNNER_DIR = "/data/local/tmp/litert";
const MAIN = process.env.LAYA_MAIN || "/sdcard/models/laya-litert/laya_ml_s256_embeds_wfp16.tflite";
const ACT = process.env.LAYA_ACT || "/sdcard/models/laya-litert/laya_ml_act_head_fp32.tflite";
const EMBED = process.env.LAYA_EMBED || "/sdcard/models/laya-litert/token_embeddings_fp16.bin";
const RUNNER_BIN = process.env.LAYA_RUNNER_BIN || "litert-runner";
const LD = `${RUNNER_DIR}:/system/lib64`;
// 本地(Termux 域)运行:LAYA_RUNNER_LOCAL=1,无需 adb;库目录默认 ~/laya-test/npu-libs
const RUNNER_LOCAL = process.env.LAYA_RUNNER_LOCAL === "1";
const LIB_DIR = process.env.LAYA_LIB_DIR || `${process.env.HOME}/laya-test/npu-libs`;

let localChild = null;

function adbSerial() {
  const out = execFileSync("adb", ["devices"], { encoding: "utf8" });
  const line = out.split("\n").find((l) => l.includes("\tdevice"));
  if (!line) throw new Error("no adb device connected (run adbre.sh)");
  return line.split("\t")[0];
}

function adbShell(serial, cmd, { bg = false } = {}) {
  const c = bg ? `nohup sh -c '${cmd}' >/data/local/tmp/litert/runner.log 2>&1 &` : cmd;
  return execFileSync("adb", ["-s", serial, "shell", c], { encoding: "utf8", timeout: 30000 });
}

/** Cheap raw TCP probe — does NOT parse the tokenizer (that costs seconds per attempt). */
function tcpProbe(socketPath, timeoutMs = 1000) {
  return new Promise((resolve) => {
    const sock = net.createConnection(socketPath);
    const done = (v) => { clearTimeout(t); sock.destroy(); resolve(v); };
    const t = setTimeout(() => done(false), timeoutMs);
    sock.once("connect", () => done(true));
    sock.once("error", () => done(false));
  });
}

/** Spawn the LiteRT runner inside adb shell (detached), then return a connected LayaNative. */
function dbg() { process.stderr.write("[litert] " + [...arguments].map(String).join(" ") + "\n"); }

export async function loadLitert({ modelDir, socketPath = { port: 7878, host: "127.0.0.1" }, cacheDir = "/data/local/tmp/gpucache" } = {}) {
  dbg("loadLitert start, socketPath=", JSON.stringify(socketPath));
  const ping = () => LayaNative.ping(socketPath);
  if (await ping()) { dbg("daemon already up"); return LayaNative.load({ modelDir, socketPath }); }

  if (RUNNER_LOCAL) {
    dbg("local mode: spawning runner in Termux domain");
    // NPU 需要 dispatch 库目录;GPU 本地模式暂不支持(NDK 版 runner 经 adb 走 shell 域)
    const env = { ...process.env, LD_LIBRARY_PATH: LIB_DIR };
    if (process.env.LAYA_BACKEND === "npu") env.LITERT_DISP_DIR = LIB_DIR;
    const args = [MAIN, ACT, EMBED, "tcp:" + socketPath.port, cacheDir];
    localChild = spawn(RUNNER_BIN, args, { env, cwd: process.cwd(), stdio: ["ignore", "ignore", "inherit"] });
    dbg("spawned", RUNNER_BIN, "pid=", localChild.pid);
  } else {
    dbg("ping failed, spawning via adb");
    const serial = adbSerial();
    adbShell(serial, `mkdir -p ${cacheDir}`);
    const sockArg = "tcp:" + socketPath.port;
    const npuEnv = process.env.LAYA_BACKEND === "npu" ? `LAYA_BACKEND=npu LITERT_DISP_DIR=${LIB_DIR} ` : "";
    const cmd = `LD_LIBRARY_PATH=${LD} ${npuEnv}${RUNNER_DIR}/${RUNNER_BIN} ${MAIN} ${ACT} ${EMBED} ${sockArg} ${cacheDir}`;
    dbg("spawn cmd=", cmd);
    adbShell(serial, cmd, { bg: true });
  }
  dbg("spawned, polling socket");

  // GPU compile: ~3s with program cache, ~6s cold, plus model load; allow 60s.
  // Probe with a cheap raw connect first; only call the full (tokenizer-parsing) load once accepted.
  for (let i = 0; i < 150; i++) {
    if (await tcpProbe(socketPath)) {
      dbg("socket accepting, doing full load");
      return LayaNative.load({ modelDir, socketPath });
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  const log = RUNNER_LOCAL ? "" : adbShell(serial, `tail -20 /data/local/tmp/litert/runner.log 2>/dev/null`).trim();
  throw new Error(`litert daemon did not come up in 60s${log ? `\n--- runner.log ---\n${log}` : ""}`);
}

export async function stopLitert() {
  if (RUNNER_LOCAL) {
    if (localChild) { try { localChild.kill("SIGTERM"); } catch {} localChild = null; }
    else { try { execFileSync("pkill", ["-f", RUNNER_BIN], { timeout: 15000 }); } catch {} }
    return;
  }
  // kill by name over adb (shell-domain process; Termux pkill can't see it)
  try { execFileSync("adb", ["-s", adbSerial(), "shell", "pkill -f litert-runner"], { timeout: 15000 }); } catch {}
}

export { LayaNative };
