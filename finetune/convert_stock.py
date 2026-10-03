#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
教师标注结果 -> 股票训练包: {prefix}-train.jsonl / {prefix}-test.jsonl / {prefix}-classes.json
输入行: {"text":..., "choice":"利好|利空|中性", "score":1-5, "risk":0|1}  (annotate_news.py 产出)
"""
import argparse
import json
import random
import sys
from collections import Counter


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default="data/stock-labeled.jsonl")
    ap.add_argument("--out-prefix", default="data/stock")
    ap.add_argument("--test-ratio", type=float, default=0.05)
    ap.add_argument("--min-test-per-class", type=int, default=100)
    ap.add_argument("--max-per-class", type=int, default=50000)
    ap.add_argument("--max-chars", type=int, default=600)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    data, seen = [], set()
    n_raw = n_bad = n_dup = 0
    with open(args.input, encoding="utf-8") as f:
        for line in f:
            n_raw += 1
            try:
                d = json.loads(line)
            except Exception:
                n_bad += 1
                continue
            text = (d.get("text") or "").strip()
            choice, score, risk = d.get("choice"), d.get("score"), d.get("risk")
            if not text or choice not in ("利好", "利空", "中性") or not (1 <= int(score or 3) <= 5) or risk is None:
                n_bad += 1
                continue
            if text in seen:
                n_dup += 1
                continue
            seen.add(text)
            data.append({
                "text": text[: args.max_chars],
                "choice": choice,
                "score": int(score),
                "risk": int(risk),
            })

    cnt = Counter(d["choice"] for d in data)
    print(f"原始 {n_raw} -> 有效 {len(data)} (坏 {n_bad}, 重复 {n_dup})")
    print("倾向分布:", dict(cnt))
    if len(cnt) < 2:
        sys.exit("错误: 有效类别不足 2 个,先多标一些数据")

    rng = random.Random(args.seed)
    train, test = [], []
    for lab in cnt:
        pool = [d for d in data if d["choice"] == lab]
        rng.shuffle(pool)
        pool = pool[: args.max_per_class]
        k = min(len(pool), max(args.min_test_per_class, int(len(pool) * args.test_ratio)))
        test += pool[:k]
        train += pool[k:]
    rng.shuffle(train)
    rng.shuffle(test)

    with open(f"{args.out_prefix}-train.jsonl", "w", encoding="utf-8") as f:
        for d in train:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    with open(f"{args.out_prefix}-test.jsonl", "w", encoding="utf-8") as f:
        for d in test:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    with open(f"{args.out_prefix}-classes.json", "w", encoding="utf-8") as f:
        json.dump({"classes": ["利好", "利空", "中性"], "normal_label": "中性",
                   "counts": {l: cnt[l] for l in cnt}}, f, ensure_ascii=False, indent=2)
    print(f"train={len(train)} test={len(test)}")
    print(f"输出: {args.out_prefix}-train.jsonl / {args.out_prefix}-test.jsonl / {args.out_prefix}-classes.json")


if __name__ == "__main__":
    main()
