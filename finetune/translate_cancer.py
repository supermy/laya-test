#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cancer 英文语料 → 教师翻译中文(llama-server Qwen,8080)。
输入: /data/ai/cancer/raw/<name>.jsonl(含 text 字段)
输出: /data/ai/cancer/raw/<name>_zh.jsonl(翻译行,保留其余字段;断点续跑)
用法: translate_cancer.py --files tcga_reports,medhallu,medhalt [--limit N]
"""
import argparse, hashlib, json, os, time, urllib.request

RAW = "/data/ai/cancer/raw"

def ask(base, text):
    body = json.dumps({
        "model": "qwen", "temperature": 0.0, "max_tokens": 900,
        "chat_template_kwargs": {"enable_thinking": False},
        "messages": [{"role": "user", "content":
            "把下面的英文医疗文本翻译成准确、简洁的中文,保留医学术语规范译法,只输出译文不要解释:\n\n" + text}],
    }).encode("utf-8")
    req = urllib.request.Request(base + "/v1/chat/completions", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        m = json.loads(r.read())["choices"][0]["message"]
        return (m.get("content") or m.get("reasoning_content") or "").strip().strip('"')

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--files", default="tcga_reports,medhallu,medhalt")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--max-chars", type=int, default=1200)
    args = ap.parse_args()

    for name in args.files.split(","):
        src = os.path.join(RAW, f"{name.strip()}.jsonl")
        out = os.path.join(RAW, f"{name.strip()}_zh.jsonl")
        if not os.path.exists(src):
            print(f"[SKIP] {src} 不存在"); continue
        rows = [json.loads(l) for l in open(src, encoding="utf-8") if l.strip()]
        done = set()
        if os.path.exists(out):  # 断点续跑:已完成 text 哈希跳过
            for l in open(out, encoding="utf-8"):
                try: done.add(json.loads(l).get("_h"))
                except Exception: pass
        todo = []
        for r in rows:
            t = (r.get("text") or "").strip()[:args.max_chars]
            if not t: continue
            h = hashlib.md5(t.encode()).hexdigest()
            if h in done: continue
            todo.append((h, t, r))
        if args.limit: todo = todo[:args.limit]
        print(f"[{name}] 待翻译 {len(todo)} / 共 {len(rows)}", flush=True)
        n, fail = 0, 0
        with open(out, "a", encoding="utf-8") as fo:
            for i, (h, t, r) in enumerate(todo):
                for attempt in range(3):
                    try:
                        zh = ask(args.base, t)
                        if zh:
                            o = dict(r); o["text"] = zh; o["_h"] = h; o["_src_en"] = t[:120]
                            fo.write(json.dumps(o, ensure_ascii=False) + "\n"); fo.flush(); n += 1
                        break
                    except Exception as e:
                        fail += 1
                        if attempt == 2: print(f"[{name}] fail {h}: {e}", flush=True)
                        time.sleep(3 * (attempt + 1))
                if (i + 1) % 50 == 0:
                    print(f"[{name}] {i+1}/{len(todo)} ok={n} fail={fail}", flush=True)
        print(f"[{name}] DONE ok={n} fail={fail}", flush=True)
    print("TRANSLATE-DONE", flush=True)

if __name__ == "__main__":
    main()
