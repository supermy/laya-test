#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cancer 数据源补抓:toyhom(git blob API,大文件)、shibing 医学百科、cblue(dirtycomputer 镜像)。"""
import json, os, time, base64, urllib.request, urllib.parse

ROOT = "/data/ai/cancer"; RAW = os.path.join(ROOT, "raw")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"; os.environ["HF_HOME"] = "/data/ai/hf-cache"
HF = "https://hf-mirror.com"
MANIFEST = {}

def write_jsonl(name, rows):
    n = 0
    with open(os.path.join(RAW, f"{name}.jsonl"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n"); n += 1
    return n

def get(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": "cancer-ds/3.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()

def gh_api(path):
    return json.loads(get("https://api.github.com" + path))

def gh_blob(repo, sha):
    o = gh_api(f"/repos/{repo}/git/blobs/{sha}")  # blob API 支持到 100MB,contents API 只到 1MB
    return base64.b64decode(o["content"])

def rows_csv(data, cap=80000):
    import csv, io
    rows = []
    for r in csv.DictReader(io.StringIO(data.decode("utf-8", "ignore"))):
        keys = list(r.keys())
        if not rows:  # 首行记录实际表头,便于诊断
            print("csv headers:", keys, flush=True)
        q = next((r[k] for k in keys if k and "question" in k.lower() or k == "问题"), "")
        a = next((r[k] for k in keys if k and "answer" in k.lower() or k == "回答"), "")
        q = (q or "").strip(); a = (a or "").strip()
        if q and a:
            rows.append({"text": f"{q}\n{a}", "title": next((r[k] for k in keys if k and "title" in k.lower()), "")})
        if len(rows) >= cap: break
    return rows

def rows_json(path, text_keys, cap=20000):
    rows = []
    data = open(path, encoding="utf-8", errors="ignore").read()
    try:
        obj = json.loads(data)
        items = obj if isinstance(obj, list) else obj.get("data", [obj])
    except Exception:
        items = []
        for line in data.splitlines():
            try: items.append(json.loads(line))
            except Exception: pass
    for o in items:
        if not isinstance(o, dict): continue
        vals = [str(o[k]).strip() for k in o if any(t == k for t in text_keys) and o.get(k)]
        t = "\n".join(v for v in vals if v)
        if t.strip():
            rows.append({"text": t[:1500], **{k: o[k] for k in o if isinstance(o[k], (str, int)) and k not in text_keys}})
        if len(rows) >= cap: break
    return rows

def dl_toyhom():
    br = gh_api("/repos/Toyhom/Chinese-medical-dialogue-data")["default_branch"]
    tree = gh_api(f"/repos/Toyhom/Chinese-medical-dialogue-data/git/trees/{br}?recursive=1")
    rows = []
    for f in tree.get("tree", []):
        if f["path"].endswith(".csv") and "Oncology" in f["path"]:
            blob = gh_blob("Toyhom/Chinese-medical-dialogue-data", f["sha"])
            rows += rows_csv(blob)
    return rows

def dl_shibing():
    from huggingface_hub import hf_hub_download
    loc = hf_hub_download("shibing624/medical", "pretrain/medical_book_zh.json", repo_type="dataset")
    return rows_json(loc, ("text", "content"), cap=15000)

def dl_cblue():
    from huggingface_hub import hf_hub_download
    repos = ["dirtycomputer/CBLUE-CHIP-STS", "dirtycomputer/CBLUE-KUAKE-QTR", "dirtycomputer/CBLUE-KUAKE-QQR", "wyp/cblue-qic"]
    rows = []
    for repo in repos:
        try:
            tree = json.loads(get(f"{HF}/api/datasets/{repo}/tree/main?recursive=true&limit=100"))
            for f in tree:
                if f.get("type") == "file" and f["path"].endswith(".json") and "train" in f["path"].lower():
                    loc = hf_hub_download(repo, f["path"], repo_type="dataset")
                    rows += rows_json(loc, ("text", "text1", "text2", "sentence1", "sentence2", "question", "report"), cap=8000)
                    print(f"cblue via {repo}/{f['path']}", flush=True)
        except Exception as e:
            print(f"cblue skip {repo}: {e}", flush=True)
    return rows

if __name__ == "__main__":
    for name, fn in (("toyhom_oncology", dl_toyhom), ("medical_book_zh", dl_shibing), ("cblue", dl_cblue)):
        t0 = time.time()
        try:
            rows = fn()
            n = write_jsonl(name, rows) if rows else 0
            MANIFEST[name] = {"rows": n, "sec": round(time.time() - t0, 1), "ok": n > 0}
            print(f"[OK] {name}: {n} rows ({MANIFEST[name]['sec']}s)", flush=True)
        except Exception as e:
            MANIFEST[name] = {"ok": False, "err": str(e)[:300]}
            print(f"[FAIL] {name}: {e}", flush=True)
        json.dump(MANIFEST, open(os.path.join(ROOT, "manifest_fix.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("FIX-DONE", json.dumps(MANIFEST, ensure_ascii=False), flush=True)
