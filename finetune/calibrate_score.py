#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""score 头真实标校准:
stage annotate:教师(llama-server)给真实样本标 severity 1-5 → raw/calib_<task>_gold.jsonl
stage calibrate:laya.Agent 取 score 问概率 → 由当前温度反解 logits → 网格搜新温度最小化真实 MAE
  改善则回写 rl_agent_config.json(备份原文件),否则保留并报告。
sec 用 NVD CVSS 直接做金标(<2→1 <4→2 <6→3 <8→4 ≥8→5),无需教师。
用法:
  calibrate_score.py annotate --task cancer --n 320
  calibrate_score.py calibrate --task cancer --model /data/ai/laya-cancer-ft
"""
import argparse, glob, json, math, os, random, time, urllib.request

RAW = "/data/ai/cancer/raw"
CRIT5 = ["无风险", "轻微", "一般", "较重", "严重"]
REAL_SOURCES = {  # 各任务的真实(非合成)样本池
    "cancer": [("toyhom_oncology.jsonl", None), ("pool_tcga_zh.jsonl", None), ("pool_medhallu_zh.jsonl", None), ("cmb_exam.jsonl", ("肿瘤", "癌", "化疗"))],
    "peritoneal": [("peritoneal_zh.jsonl", None), ("pool_peritoneal_zh.jsonl", None)],
    "sec": [("sec_cve.jsonl", None), ("sec_phish.jsonl", None), ("sec_attack.jsonl", None)],
}

def ask_server(base, text):
    body = json.dumps({
        "model": "qwen", "temperature": 0.0, "max_tokens": 20,
        "chat_template_kwargs": {"enable_thinking": False},
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content":
            "评估这段文本的风险严重程度(1-5:1无风险/2轻微/3一般/4较重/5严重)。"
            "只输出JSON:{\"severity\":N}\n\n" + text[:800]}],
    }).encode("utf-8")
    req = urllib.request.Request(base + "/v1/chat/completions", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        c = json.loads(r.read())["choices"][0]["message"].get("content", "")
        return int(json.loads(c).get("severity", 0))

def real_pool(task, n):
    rows = []
    for name, kws in REAL_SOURCES[task]:
        p = os.path.join(RAW, name)
        if not os.path.exists(p): continue
        for l in open(p, encoding="utf-8"):
            r = json.loads(l)
            t = r.get("text", "")
            if len(t) < 40: continue
            if kws and not any(k in t for k in kws): continue
            if task == "sec" and name == "sec_cve.jsonl":
                rows.append({"text": t[:800], "gold": min(5, max(1, round(float(r.get("cvss") or 0) / 2 + 0.5)))})
            else:
                rows.append({"text": t[:800]})
            if len(rows) >= n * 2: break
        if len(rows) >= n * 2: break
    random.Random(5).shuffle(rows)
    return rows[:n]

def stage_annotate(args):
    assert args.task != "sec", "sec 用 CVSS 金标,无需教师标注"
    rows = real_pool(args.task, args.n)
    print(f"教师标注 {len(rows)} 条({args.task})", flush=True)
    out = os.path.join(RAW, f"calib_{args.task}_gold.jsonl")
    done = set()
    if os.path.exists(out):
        for l in open(out, encoding="utf-8"):
            try: done.add(json.loads(l)["text"][:80])
            except Exception: pass
    ok = fail = 0
    with open(out, "a", encoding="utf-8") as f:
        for i, r in enumerate(rows):
            if r["text"][:80] in done: continue
            for attempt in range(3):
                try:
                    s = ask_server(args.base, r["text"])
                    if 1 <= s <= 5:
                        f.write(json.dumps({"text": r["text"], "gold": s}, ensure_ascii=False) + "\n"); f.flush(); ok += 1
                    break
                except Exception:
                    time.sleep(2 * (attempt + 1))
                fail += 1
            if (i + 1) % 50 == 0: print(f"  {i+1}/{len(rows)} ok={ok}", flush=True)
    print(f"标注完成 ok={ok} fail={fail} → {out}", flush=True)

def stage_calibrate(args):
    import laya
    agent = laya.Agent(args.model, device="cuda" if os.environ.get("CUDA_VISIBLE_DEVICES", "0") != "-1" else "cpu")
    cfg_path = os.path.join(args.model, "rl_agent_config.json")
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    qdefs = cfg["question_defs"]
    score_q = next(q for q in qdefs if q["type"] == "score")
    qid = score_q["qid"]
    t_idx = [q["qid"] for q in qdefs].index(qid)
    t_cur = cfg.get("temperature", [1, 1, 1])[t_idx] if isinstance(cfg.get("temperature"), list) else float(cfg.get("temperature", 1))
    task = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), f"task_{args.task}.json"), encoding="utf-8"))
    questions = {q["qid"]: q for q in qdefs}

    p = os.path.join(RAW, f"calib_{args.task}_gold.jsonl")
    rows = [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
    print(f"真实金标 {len(rows)} 条 | 当前 score 温度={t_cur}", flush=True)

    data = []  # (probs list[5], gold)
    for i, r in enumerate(rows):
        res = agent.predict({task["state_key"]: r["text"]}, questions)
        a = res["answers"].get(qid)
        if not a: continue
        probs = a.get("probabilities") or {}
        pv = [float(probs.get(str(i), probs.get(c, 0.0))) for i, c in enumerate(score_q.get("criteria") or CRIT5)]
        s = sum(pv)
        if s <= 0 or len(pv) != 5: continue
        data.append(([x / s for x in pv], int(r["gold"])))
        if (i + 1) % 100 == 0: print(f"  推理 {i+1}/{len(rows)}", flush=True)
    print(f"有效样本 {len(data)}", flush=True)

    def stats(T):
        errs, bias = [], 0.0
        for pv, gold in data:
            z = [t_cur * math.log(max(x, 1e-9)) for x in pv]
            q = [math.exp(x / T) for x in z]; ssum = sum(q)
            e = sum((i + 1) * q[i] / ssum for i in range(5))
            errs.append(abs(e - gold)); bias += e - gold
        return sum(errs) / len(errs), bias / len(errs)

    mae0, bias0 = stats(t_cur)
    best_T, best_mae = t_cur, mae0
    T = 0.30
    while T <= 3.0:
        m, _ = stats(T)
        if m < best_mae: best_mae, best_T = m, T
        T = round(T + 0.02, 2)
    mae1, bias1 = stats(best_T)
    print(f"原温度 {t_cur}: MAE={mae0:.3f} bias={bias0:+.3f}")
    print(f"最优温度 {best_T}: MAE={best_mae:.3f} bias={bias1:+.3f}")

    if best_mae < mae0 - 0.02:
        bak = cfg_path + ".bak"
        if not os.path.exists(bak): open(bak, "w").write(json.dumps(cfg, ensure_ascii=False, indent=2))
        if isinstance(cfg.get("temperature"), list):
            cfg["temperature"][t_idx] = best_T
        else:
            cfg["temperature"] = best_T
        json.dump(cfg, open(cfg_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        print(f"已回写 {cfg_path} score 温度 → {best_T}(备份 {bak})", flush=True)
    else:
        print("真实标注上温度无可改善(偏差为分布问题,非锐度问题),保留原温度", flush=True)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["annotate", "calibrate"])
    ap.add_argument("--task", required=True)
    ap.add_argument("--model", default=None)
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--n", type=int, default=320)
    args = ap.parse_args()
    if args.stage == "annotate":
        stage_annotate(args)
    else:
        assert args.model, "calibrate 需要 --model"
        stage_calibrate(args)
