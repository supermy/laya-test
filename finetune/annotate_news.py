#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
股票资讯教师标注 — 本地大模型(llama-server)一次给出三问标签: 倾向/重要性/风险
输出行: {"text":..., "choice":"利好|利空|中性", "score":1-5, "risk":0|1}

蒸馏范式: Laya 官方 typed-decisions checkpoint 即由教师标注微调而来。
前置: llama-server 起在 --base(见 run_annotate.sh)
用法:
  python3 annotate_news.py                       # 全量,断点续跑
  python3 annotate_news.py --two-pass            # 倾向正反序双问,不一致丢弃(消位置偏置,推荐)
  python3 annotate_news.py --limit 300           # 试水
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.request

SYSTEM = (
    "你是资深证券分析师。阅读财经新闻,输出 JSON(不要解释不要多余文本):\n"
    '{"t":"利好|利空|中性","s":1到5的整数,"r":0或1}\n'
    't=对相关股票的倾向(利好=业绩增长/大额订单/政策扶持/产品突破等正面驱动;'
    '利空=立案调查/业绩下滑/减持/处罚/行业受限等负面驱动;中性=事实陈述或影响不明)\n'
    's=该新闻对股价影响的重要性(1无关紧要~5重大)\n'
    'r=是否包含重大风险警示(立案调查、减持、业绩爆雷、处罚等:1是,0否)'
)


def ask(base, model, text, swap):
    opts = "t 选项顺序: 利好/利空/中性" if not swap else "t 选项顺序: 利空/利好/中性"
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM + "\n" + opts},
            {"role": "user", "content": text},
        ],
        "temperature": 0.0,
        "max_tokens": 40,
    }).encode("utf-8")
    req = urllib.request.Request(base + "/v1/chat/completions", body,
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"].strip()


def parse(raw, swap):
    m = re.search(r"\{[^{}]*\}", raw, re.S)
    if not m:
        return None
    try:
        o = json.loads(m.group(0))
    except Exception:
        return None
    t = str(o.get("t", "")).strip()
    if swap:
        t = {"利好": "利空", "利空": "利好"}.get(t, t)  # 反转序还原
    if t not in ("利好", "利空", "中性"):
        return None
    try:
        s = max(1, min(5, int(o.get("s", 3))))
    except Exception:
        s = 3
    r = 1 if str(o.get("r", 0)) in ("1", "1.0", "true") else 0
    return {"choice": t, "score": s, "risk": r}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", default="data/stock-pool.jsonl")
    ap.add_argument("--out", default="data/stock-labeled.jsonl")
    ap.add_argument("--base", default="http://127.0.0.1:8180")
    ap.add_argument("--model", default="qwen")
    ap.add_argument("--two-pass", action="store_true", help="倾向正反序双问,不一致丢弃")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    done = set()
    if os.path.exists(args.out):
        with open(args.out, encoding="utf-8") as f:
            for line in f:
                try:
                    done.add(hashlib.md5(json.loads(line)["text"].encode()).hexdigest())
                except Exception:
                    pass
    print(f"断点续跑: 已有 {len(done)} 条", flush=True)

    n_ok = n_skip = n_bad = 0
    t0 = time.time()
    with open(args.pool, encoding="utf-8") as fin, open(args.out, "a", encoding="utf-8") as fout:
        for line in fin:
            if args.limit and n_ok >= args.limit:
                break
            try:
                text = json.loads(line)["text"]
            except Exception:
                continue
            h = hashlib.md5(text.encode()).hexdigest()
            if h in done:
                n_skip += 1
                continue
            try:
                a = parse(ask(args.base, args.model, text, False), False)
                if args.two_pass and a is not None:
                    b = parse(ask(args.base, args.model, text, True), True)
                    if not b or b["choice"] != a["choice"]:
                        n_bad += 1
                        continue
            except Exception as e:
                print(f"请求失败,10s 后继续: {e}", file=sys.stderr, flush=True)
                time.sleep(10)
                continue
            if a is None:
                n_bad += 1
                continue
            fout.write(json.dumps({"text": text, **a}, ensure_ascii=False) + "\n")
            fout.flush()
            done.add(h)
            n_ok += 1
            if n_ok % 100 == 0:
                print(f"已标注 {n_ok} 条 ({n_ok/(time.time()-t0):.2f} 条/s)", flush=True)

    from collections import Counter
    cnt = Counter()
    with open(args.out, encoding="utf-8") as f:
        for line in f:
            try:
                cnt[json.loads(line)["choice"]] += 1
            except Exception:
                pass
    print(f"完成: 新增 {n_ok},跳过 {n_skip},无效/不一致丢弃 {n_bad}", flush=True)
    print("倾向分布:", dict(cnt))


if __name__ == "__main__":
    main()
