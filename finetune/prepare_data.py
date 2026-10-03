#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
原始短信数据 -> Laya 微调格式(train/test/classes.json)

输入: CSV(带表头, 或 --no-header 时 text/label 列用下标) 或 JSONL
输出: {prefix}-train.jsonl / {prefix}-test.jsonl / {prefix}-classes.json

统一类别体系建议:
  4 类: 正常 / 推销广告 / 诈骗 / 其他骚扰
  2 类: 正常 / 垃圾短信
用 --label-map 把原始标签归一到统一体系(未映射的标签原样保留), 例如:
  python prepare_data.py --input raw.csv --text-col text --label-col label \
    --label-map '0:正常,1:推销广告,2:诈骗,3:其他骚扰' --normal-label 正常
"""
import argparse
import csv
import json
import random
import sys
from collections import Counter


def iter_rows(path, text_col, label_col, no_header):
    if path.endswith(".jsonl"):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    d = json.loads(line)
                    yield str(d[text_col]), str(d[label_col])
        return
    with open(path, encoding="utf-8", newline="") as f:
        if no_header:
            ti, li = int(text_col), int(label_col)
            for row in csv.reader(f):
                if len(row) > max(ti, li):
                    yield row[ti], row[li]
        else:
            for row in csv.DictReader(f):
                yield str(row.get(text_col, "")), str(row.get(label_col, ""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="原始数据 CSV/JSONL")
    ap.add_argument("--text-col", default="text")
    ap.add_argument("--label-col", default="label")
    ap.add_argument("--no-header", action="store_true", help="CSV 无表头, 列名用整数下标")
    ap.add_argument("--label-map", default=None, help="'原标签:统一标签,...' 标签归一化映射")
    ap.add_argument("--normal-label", default="正常", help="非垃圾类的标签名")
    ap.add_argument("--out-prefix", default="sms")
    ap.add_argument("--test-ratio", type=float, default=0.05)
    ap.add_argument("--min-test-per-class", type=int, default=50)
    ap.add_argument("--max-per-class", type=int, default=50000, help="每类上限(类不平衡时截断)")
    ap.add_argument("--max-chars", type=int, default=700, help="文本截断长度(足够覆盖 512 token)")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    lmap = {}
    if args.label_map:
        for kv in args.label_map.split(","):
            k, v = kv.split(":", 1)
            lmap[k.strip()] = v.strip()

    data, seen = [], set()
    n_raw = n_bad = n_dup = 0
    for text, label in iter_rows(args.input, args.text_col, args.label_col, args.no_header):
        n_raw += 1
        text, label = text.strip(), lmap.get(label.strip(), label.strip())
        if not text or not label:
            n_bad += 1
            continue
        if text in seen:
            n_dup += 1
            continue
        seen.add(text)
        data.append((text[: args.max_chars], label))

    cnt = Counter(l for _, l in data)
    print(f"原始 {n_raw} 条 -> 有效 {len(data)} 条(空/坏 {n_bad}, 重复 {n_dup})")
    print("类别分布:", dict(cnt.most_common()))
    if args.normal_label not in cnt:
        sys.exit(f"错误: normal-label '{args.normal_label}' 不在数据中, 现有 {list(cnt)}")
    if len(cnt) < 2:
        sys.exit("错误: 至少需要 2 个类别")

    rng = random.Random(args.seed)
    train, test = [], []
    for lab in cnt:
        pool = [t for t, l in data if l == lab]
        rng.shuffle(pool)
        pool = pool[: args.max_per_class]
        k = min(len(pool), max(args.min_test_per_class, int(len(pool) * args.test_ratio)))
        test += [(t, lab) for t in pool[:k]]
        train += [(t, lab) for t in pool[k:]]
    rng.shuffle(train)
    rng.shuffle(test)

    others = sorted([l for l in cnt if l != args.normal_label], key=lambda l: (-cnt[l], l))
    classes = [args.normal_label] + others
    with open(f"{args.out_prefix}-train.jsonl", "w", encoding="utf-8") as f:
        for t, l in train:
            f.write(json.dumps({"text": t, "label": l}, ensure_ascii=False) + "\n")
    with open(f"{args.out_prefix}-test.jsonl", "w", encoding="utf-8") as f:
        for t, l in test:
            f.write(json.dumps({"text": t, "label": l}, ensure_ascii=False) + "\n")
    with open(f"{args.out_prefix}-classes.json", "w", encoding="utf-8") as f:
        json.dump(
            {
                "classes": classes,
                "normal_label": args.normal_label,
                "counts": {l: cnt[l] for l in classes},
            },
            f,
            ensure_ascii=False,
            indent=2,
        )
    print(f"train={len(train)} test={len(test)}")
    print(f"classes(顺序即 choice criteria 顺序) = {classes}")
    print(f"输出: {args.out_prefix}-train.jsonl / {args.out_prefix}-test.jsonl / {args.out_prefix}-classes.json")


if __name__ == "__main__":
    main()
