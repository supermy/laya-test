#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
微调模型在测试集上的指标(任务驱动,支持 choice/score/noul 任意组合):
  choice: 准确率 + 每类 P/R/F1 + 混淆矩阵
  score : MAE + within-1
  noul  : P/R/F1(阈值可调) + ECE

用法:
  python evaluate.py --model laya-sms-ft --data data/sms-test.jsonl --classes data/sms-classes.json --task sms
  python evaluate.py --model laya-stock-ft --data data/stock-test.jsonl --classes data/stock-classes.json --task stock
"""
import argparse
import json
import os
import time
from collections import Counter

import numpy as np
import torch


def ece_score(conf, correct, bins=15):
    conf, correct = np.asarray(conf), np.asarray(correct)
    ece = 0.0
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        m = (conf > lo) & (conf <= hi)
        if m.sum():
            ece += m.mean() * abs(correct[m].mean() - conf[m].mean())
    return float(ece)


def prf(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="微调输出目录")
    ap.add_argument("--data", default="sms-test.jsonl")
    ap.add_argument("--classes", default="sms-classes.json")
    ap.add_argument("--task", default="sms", help="任务名(task_*.json,取 from/derive 定位真值)")
    ap.add_argument("--threshold", type=float, default=0.5, help="noul 判真阈值")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--out", default=None, help="指标 JSON 输出路径")
    args = ap.parse_args()

    import laya

    agent = laya.Agent(args.model, device="cuda" if torch.cuda.is_available() else "cpu")

    with open(args.classes, encoding="utf-8") as f:
        meta = json.load(f)
    classes, normal = meta["classes"], meta["normal_label"]

    # 问题定义取模型 config(保证与训练一字不差),真值定位取任务文件的 from/derive
    cfg_path = os.path.join(args.model, "rl_agent_config.json")
    with open(cfg_path, encoding="utf-8") as f:
        qdefs = json.load(f)["question_defs"]
    task_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"task_{args.task}.json")
    with open(task_path, encoding="utf-8") as f:
        task = json.load(f)
    tmap = {t["qid"]: t for t in task["questions"]}
    questions = {q["qid"]: q for q in qdefs}

    with open(args.data, encoding="utf-8") as f:
        rows = [json.loads(l) for l in f if l.strip()]
    if args.limit:
        rows = rows[: args.limit]
    print(f"测试 {len(rows)} 条 | threshold={args.threshold}", flush=True)

    for r in rows[:3]:  # warmup
        agent.predict({task["state_key"]: r["text"]}, questions)

    n = 0
    choice_qid = next((q["qid"] for q in qdefs if q["type"] == "choice"), None)
    cm = Counter()
    score_err, score_w1 = [], []
    tp = fp = fn = 0
    confs, corr = [], []
    lats = []
    for i, row in enumerate(rows):
        state = {t["state_key"]: row["text"] for t in [task]}
        s = time.perf_counter()
        res = agent.predict(state, questions)
        lats.append((time.perf_counter() - s) * 1000)
        ans = res["answers"]
        n += 1

        for q in qdefs:
            t = tmap[q["qid"]]
            gold = row.get(t.get("from", "label"))
            pred = ans.get(q["qid"])
            if pred is None or gold is None:
                continue
            if q["type"] == "choice":
                cm[(gold, pred["choice"])] += 1
            elif q["type"] == "score":
                err = abs(float(pred["score"]) - float(gold))
                score_err.append(err)
                score_w1.append(float(err <= 1.0))
            else:  # noul
                g = (1 if gold != normal else 0) if t.get("derive") == "not_normal" else int(gold)
                p_true = float(pred["noul"])
                pred1 = p_true >= args.threshold
                tp += pred1 and g == 1
                fp += pred1 and g == 0
                fn += (not pred1) and g == 1
                confs.append(max(p_true, 1 - p_true))
                corr.append(float(pred1 == g))
        if (i + 1) % 500 == 0:
            print(f"  {i+1}/{len(rows)}", flush=True)

    summary = {"model": args.model, "n": n}

    if choice_qid:
        correct = sum(v for (g, p), v in cm.items() if g == p)
        acc = correct / n
        per = {}
        for c in classes:
            tp_c = cm[(c, c)]
            fp_c = sum(v for (g, p), v in cm.items() if p == c and g != c)
            fn_c = sum(v for (g, p), v in cm.items() if g == c and p != c)
            p, r, f = prf(tp_c, fp_c, fn_c)
            per[c] = {"precision": round(p, 4), "recall": round(r, 4), "f1": round(f, 4),
                      "support": sum(v for (g, _), v in cm.items() if g == c)}
        macro_f1 = round(float(np.mean([v["f1"] for v in per.values()])), 4)
        w = max(len(c) for c in classes) + 1
        print("\n混淆矩阵(行=真实, 列=预测):")
        print(" " * (w + 2) + "".join(f"{c:>{w}}" for c in classes))
        for g in classes:
            print(f"{g:>{w}} " + "  " + "".join(f"{cm[(g, p)]:>{w}}" for p in classes))
        print(f"\nchoice 准确率: {acc:.4f} | macro-F1: {macro_f1}")
        for c, v in per.items():
            print(f"  {c}: P={v['precision']:.4f} R={v['recall']:.4f} F1={v['f1']:.4f} (n={v['support']})")
        summary["choice"] = {"acc": round(acc, 4), "macro_f1": macro_f1, "per_class": per}

    if score_err:
        mae = float(np.mean(score_err))
        w1 = float(np.mean(score_w1))
        print(f"\nscore MAE: {mae:.3f} | within-1: {w1:.4f}")
        summary["score"] = {"mae": round(mae, 3), "within_1": round(w1, 4)}

    if confs:
        p_s, r_s, f_s = prf(tp, fp, fn)
        ece = ece_score(confs, corr)
        print(f"\nnoul @阈值{args.threshold}: P={p_s:.4f} R={r_s:.4f} F1={f_s:.4f}")
        print(f"noul ECE: {ece:.4f}")
        summary["noul"] = {"threshold": args.threshold, "precision": round(p_s, 4),
                           "recall": round(r_s, 4), "f1": round(f_s, 4), "ece": round(ece, 4)}

    lats = np.array(lats)
    print(f"\n延迟 ms: p50={np.percentile(lats, 50):.1f} avg={lats.mean():.1f}")
    summary["latency_ms"] = {"p50": round(float(np.percentile(lats, 50)), 1), "avg": round(float(lats.mean()), 1)}

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)
        print(f"指标已写: {args.out}")


if __name__ == "__main__":
    main()
