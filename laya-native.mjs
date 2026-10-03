// Native Laya runner client: JS prep (tokenize/sequence) -> resident C daemon via Unix socket
// Same request/response shape as @receptron/laya's systemOne.
import { Tokenizer } from "@huggingface/tokenizers";
import { buildSequence, confidenceFromProbs, QTYPES, renderOptions, softmax, tempBucket, toInternal } from "./node_modules/@receptron/laya/dist/sequence.js";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

const MAGIC = 0x4c415941;
const HDR_REQ = 20, HDR_RSP = 16;
const round4 = (x) => Math.round(x * 1e4) / 1e4;

export class LayaNative {
  constructor(sock, tok, config, ids) {
    this.sock = sock; this.tok = tok; this.config = config; this.ids = ids;
  }
  encode(text) { return this.tok.encode(text, { add_special_tokens: false }).ids; }

  static async load({ modelDir, modelPath, socketPath, threads = 6 }) {
    const read = (f) => JSON.parse(fs.readFileSync(path.join(modelDir, f), "utf8"));
    const config = read("laya_config.json");
    const tok = new Tokenizer(read("tokenizer/tokenizer.json"), read("tokenizer/tokenizer_config.json"));
    const pick = (...names) => {
      for (const n of names) { const v = tok.token_to_id(n); if (v !== undefined) return v; }
      return undefined;
    };
    const st = config.special_tokens ?? {};
    const clsT = st.cls ?? (pick("[CLS]") !== undefined ? "[CLS]" : "<bos>");
    const sepT = st.sep ?? (pick("[SEP]") !== undefined ? "[SEP]" : "</s>");
    const maskT = st.mask ?? (pick("[MASK]") !== undefined ? "[MASK]" : "<mask>");
    const padT = st.pad ?? (pick("[PAD]") !== undefined ? "[PAD]" : "<pad>");
    const id = (t) => {
      const v = tok.token_to_id(t);
      if (v === undefined) throw new Error(`special token ${t} missing`);
      return v;
    };
    const ids = { cls: id(clsT), sep: id(sepT), mask: id(maskT), pad: id(padT), maskTok: maskT };

    const sock = net.createConnection(socketPath);
    await new Promise((res, rej) => {
      sock.once("connect", res);
      sock.once("error", (e) => rej(Object.assign(e, { code: "E_NO_DAEMON" })));
    });
    const inst = new LayaNative(sock, tok, config, ids);
    inst._setupReceiver();
    return inst;
  }

  /** Ping the daemon; resolves true if alive. No model loading involved. */
  static async ping(socketPath) {
    return new Promise((resolve) => {
      const sock = net.createConnection(socketPath);
      const done = (v) => { clearTimeout(timer); try { sock.destroy(); } catch {} resolve(v); };
      const timer = setTimeout(() => done(false), 2000);
      sock.once("connect", () => {
        const hdr = Buffer.alloc(HDR_REQ);
        hdr.writeUInt32LE(MAGIC, 0);
        hdr.writeUInt8(1, 4); // ping
        const frame = Buffer.alloc(4);
        frame.writeUInt32LE(HDR_REQ, 0);
        sock.write(Buffer.concat([frame, hdr]));
      });
      sock.on("data", (d) => done(d.length >= 8 && d.readUInt32LE(4) === MAGIC));
      sock.once("error", () => done(false));
    });
  }

  /** Spawn the C daemon (if not already up) and return a connected client. */
  static async loadWithDaemon({ modelDir, modelPath, socketPath, threads = 6, runnerBin, env }) {
    // cheap raw-connect probe first: LayaNative.load parses a 30MB+ tokenizer, so
    // retrying with full loads costs seconds per attempt
    let up = await LayaNative.ping(socketPath);
    if (!up) {
      // a daemon may be mid-load; give it up to 3s before spawning another
      for (let i = 0; i < 15 && !up; i++) {
        await new Promise((r) => setTimeout(r, 200));
        up = await LayaNative.ping(socketPath);
      }
      if (!up) {
        const bin = runnerBin ?? new URL("./runner", import.meta.url).pathname;
        const child = spawn(bin, [modelPath, socketPath, String(threads)], {
          stdio: ["ignore", "ignore", "inherit"],
          detached: true, // survive the CLI process; stop via `cmd 2` / laya stop
          env: env ?? process.env,
        });
        child.unref();
        await new Promise((res, rej) => {
          child.once("spawn", res);
          child.once("error", rej);
          child.once("exit", (c) => rej(new Error(`runner exited early (${c})`)));
        });
        for (let i = 0; i < 300 && !up; i++) {
          await new Promise((r) => setTimeout(r, 100));
          up = await LayaNative.ping(socketPath);
        }
        if (!up) throw new Error("daemon did not come up in 30s");
      }
    }
    return LayaNative.load({ modelDir, socketPath });
  }

  async infer(items) {
    // pad to batch max, same layout as rl_common.collate_items
    const n = items.length;
    const L = this.config.fixed_seq_len ?? Math.max(...items.map((it) => it.ids.length));
    const K = Math.max(...items.map((it) => it.markers.length));
    const inputIds = new BigInt64Array(n * L).fill(BigInt(this.ids.pad));
    const attention = new BigInt64Array(n * L);
    const markerPos = new BigInt64Array(n * K);
    const markerMask = new Uint8Array(n * K);
    const qtype = new BigInt64Array(n);
    items.forEach((it, i) => {
      it.ids.forEach((v, j) => { inputIds[i * L + j] = BigInt(v); attention[i * L + j] = 1n; });
      it.markers.forEach((m, j) => { markerPos[i * K + j] = BigInt(m); markerMask[i * K + j] = 1; });
      qtype[i] = BigInt(it.qtype);
    });

    const hdr = Buffer.alloc(HDR_REQ);
    hdr.writeUInt32LE(MAGIC, 0);
    hdr.writeUInt8(0, 4); // infer
    hdr.writeUInt32LE(n, 8);
    hdr.writeUInt32LE(L, 12);
    hdr.writeUInt32LE(K, 16);
    const payload = [inputIds, attention, markerPos, markerMask, qtype].map((a) => Buffer.from(a.buffer, a.byteOffset, a.byteLength));
    const body = Buffer.concat([hdr, ...payload]);
    const frame = Buffer.alloc(4);
    frame.writeUInt32LE(body.length, 0);
    this.sock.write(Buffer.concat([frame, body]));

    const total = (await this._readExact(4)).readUInt32LE(0);
    const rsp = await this._readExact(total);
    if (rsp.readUInt32LE(0) !== MAGIC) throw new Error("bad magic from runner");
    const status = rsp.readUInt8(4);
    if (status !== 0) {
      const msgLen = rsp.readUInt32LE(12);
      throw new Error(`runner error: ${rsp.subarray(HDR_RSP, HDR_RSP + msgLen).toString("utf8")}`);
    }
    const actDim = rsp.readUInt8(5);
    const nOut = rsp.readUInt32LE(8), kOut = rsp.readUInt32LE(12);
    const logits = new Float32Array(rsp.buffer, rsp.byteOffset + HDR_RSP, nOut * kOut);
    const actRaw = new Float32Array(rsp.buffer, rsp.byteOffset + HDR_RSP + nOut * kOut * 4, nOut * actDim);
    return { logits, actRaw, actDim, n: nOut, K: kOut };
  }

  _setupReceiver() {
    if (this._rx) return;
    this._rx = Buffer.alloc(0);
    this._waiters = [];
    this.sock.on("data", (d) => {
      this._rx = this._rx.length === 0 ? d : Buffer.concat([this._rx, d]);
      this._pump();
    });
  }
  _pump() {
    while (this._waiters.length > 0 && this._rx.length >= this._waiters[0].len) {
      const w = this._waiters.shift();
      const out = this._rx.subarray(0, w.len);
      this._rx = this._rx.subarray(w.len);
      w.res(out);
    }
  }
  _readExact(len) {
    if (this._rx && this._rx.length >= len) {
      const out = this._rx.subarray(0, len);
      this._rx = this._rx.subarray(len);
      this._pump();
      return Promise.resolve(out);
    }
    return new Promise((res) => {
      this._waiters.push({ len, res });
      this._pump();
    });
  }

  async systemOne(state, questions) {
    const p = (this._chain ?? Promise.resolve()).then(
      () => this._systemOneInner(state, questions),
      () => this._systemOneInner(state, questions),
    );
    this._chain = p.catch(() => {});
    return p;
  }

  /**
   * 从 config.question_defs 里挑出 SMS 判别所需的两问(choice 含"短信" + noul 含"垃圾/骚扰")。
   * 兼容两种产物:微调 checkpoint(2 条 qdefs)与多技能导出(stock+sms 共 5 条)。
   */
  smsQuestionsFromConfig() {
    const qdefs = this.config.question_defs;
    if (!Array.isArray(qdefs) || qdefs.length < 2) {
      throw new Error("config.question_defs 缺失:该模型没有内置问题定义");
    }
    const choiceQ = qdefs.find((q) => q.type === "choice" && /短信/.test(q.instructions || ""));
    const noulQ = qdefs.find((q) => q.type === "noul" && /垃圾|骚扰/.test(q.instructions || ""));
    if (!choiceQ || !noulQ) throw new Error("question_defs 里没有 SMS 判别问题(choice短信/noul垃圾)");
    return { s0: choiceQ, s1: noulQ };
  }

  /**
   * 垃圾短信判别。state={"sms":text} + 固定双问(choice 类别 + noul 垃圾)。
   * 返回 verdict: {label, probabilities, spamProb, isSpam, spamByChoice}。
   * questions 可覆盖(如用现有 multi 模型跑 SMS 形状输入做管路测试)。
   */
  async smsInfer(text, questions) {
    const qs = questions ?? this.smsQuestionsFromConfig();
    const r = await this.systemOne({ sms: text }, qs);
    const keys = Object.keys(r.answers).sort();
    const a0 = r.answers[keys[0]], a1 = r.answers[keys[1]];
    // 回复按问题类型对号,不按 qid 顺序(多技能导出时 qid 不再是 q0/q1)
    const choiceAns = a0.type === "choice" ? a0 : a1;
    const noulAns = a0.type === "choice" ? a1 : a0;
    const normal = this.config.normal_label;
    const threshold = this.config.spam_threshold ?? 0.5;
    const spamProb = noulAns?.noul ?? 0;
    const spamByChoice = normal !== undefined && choiceAns.choice !== normal;
    return {
      answers: r.answers,
      verdict: {
        label: choiceAns.choice,
        probabilities: choiceAns.probabilities,
        spamByChoice,
        spamProb,
        isSpam: spamByChoice && spamProb >= threshold,
      },
      usage: r.usage,
      _inferMs: r._inferMs,
    };
  }

  async _systemOneInner(state, questions) {
    const qids = Object.keys(questions);
    if (this.config.batch1 && qids.length > 1) {
      const answers = {};
      let tokens = 0, ms = 0;
      for (const qid of qids) {
        const r = await this._systemOneInner(state, { [qid]: questions[qid] });
        answers[qid] = r.answers[qid];
        tokens += r.usage.input_tokens;
        ms += r._inferMs ?? 0;
      }
      return { answers, usage: { input_tokens: tokens }, _inferMs: ms };
    }
    const _tPrep0 = performance.now();
    const items = qids.map((qid) => {
      const q = toInternal(questions[qid]);
      const { ids, markers } = buildSequence(this.encode.bind(this), this.ids, state, q, this.config.max_len, this.config.head_max_len);
      if (markers.length !== renderOptions(q).length) throw new Error(`question ${qid}: options do not fit in head_max_len`);
      return { qid, q, ids, markers, qtype: QTYPES[q.t] };
    });
    const prepMs = performance.now() - _tPrep0;
    const t0 = performance.now();
    const { logits, actRaw, actDim, n, K } = await this.infer(items);
    const inferMs = performance.now() - t0;
    const _tPost0 = performance.now();

    const answers = {};
    items.forEach((it, r) => {
      const k = it.markers.length;
      const temp = this.config.temperature_by_options[tempBucket(it.qtype, k)] ?? this.config.temperature[it.qtype] ?? 1;
      const p = softmax(Array.from(logits.subarray(r * K, r * K + k), (v) => v / temp));
      // act_logits[n,2] -> P(act) via binary softmax; act_probs[n,1] -> take as-is
      const act_probability = actDim === 2 ? (() => { const m = Math.max(actRaw[r * 2], actRaw[r * 2 + 1]); const e0 = Math.exp(actRaw[r * 2] - m), e1 = Math.exp(actRaw[r * 2 + 1] - m); return e0 / (e0 + e1); })() : (actRaw[r] ?? 0);
      const q = it.q;
      if (q.t === "choice") {
        const keys = Object.keys(q.crit);
        const best = p.indexOf(Math.max(...p));
        answers[it.qid] = { type: "choice", choice: keys[best], probabilities: Object.fromEntries(keys.map((kk, i) => [kk, round4(p[i] ?? 0)])), confidence: round4(confidenceFromProbs(p)), rl_agent: { act_probability: act_probability } };
      } else if (q.t === "score") {
        answers[it.qid] = { type: "score", score: round4(p.reduce((s, v, i) => s + i * v, 0)), legend: Object.fromEntries(q.crit.map((c, i) => [String(i), c])), probabilities: Object.fromEntries(p.map((v, i) => [String(i), round4(v)])), confidence: round4(confidenceFromProbs(p)), rl_agent: { act_probability: act_probability } };
      } else {
        answers[it.qid] = { type: "noul", noul: round4(p[1] ?? 0), rl_agent: { act_probability: act_probability } };
      }
    });
    return { answers, usage: { input_tokens: items.reduce((s, it) => s + it.ids.length, 0) }, _inferMs: inferMs, _prepMs: prepMs, _postMs: performance.now() - _tPost0 };
  }

  async shutdown() {
    const hdr = Buffer.alloc(HDR_REQ);
    hdr.writeUInt32LE(MAGIC, 0);
    hdr.writeUInt8(2, 4); // exit
    this.sock.write(hdr);
    this.sock.end();
  }
}
