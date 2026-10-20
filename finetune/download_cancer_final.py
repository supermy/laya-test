#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cancer 数据源终补:TCGA-Reports(rosenthal/tcga-path-notes)+ Toyhom 肿瘤科(ticoAg 全量对话按科室过滤)。"""
import json, os, time
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"; os.environ["HF_HOME"] = "/data/ai/hf-cache"
ROOT = "/data/ai/cancer"; RAW = os.path.join(ROOT, "raw")
MANIFEST = {}

def write_jsonl(name, rows):
    n = 0
    with open(os.path.join(RAW, f"{name}.jsonl"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n"); n += 1
    return n

def dl_tcga():
    from huggingface_hub import hf_hub_download
    loc = hf_hub_download("rosenthal/tcga-path-notes", "data/TCGA_Reports.csv", repo_type="dataset")
    import csv, io
    rows = []
    with open(loc, encoding="utf-8", errors="ignore") as fh:
        for r in csv.DictReader(fh):
            if not rows: print("tcga headers:", list(r.keys()), flush=True)
            t = next((r[k] for k in r if k and ("report" in k.lower() or "text" in k.lower())), "")
            t = (t or "").strip()
            if len(t) > 80:
                meta = {k: r[k] for k in r if k not in (t,) and isinstance(r[k], str) and len(r[k]) < 80 and k.lower() not in ("report", "text")}
                rows.append({"text": t[:2000], **meta})
    return rows

def dl_toyhom():
    """ticoAg 全量对话 634MB,HF 直下,按 dept 过滤肿瘤科"""
    from huggingface_hub import hf_hub_download
    loc = hf_hub_download("ticoAg/Chinese-medical-dialogue", "data/train_0001_of_0001.json", repo_type="dataset")
    rows = []
    data = open(loc, encoding="utf-8", errors="ignore").read()
    try:
        items = json.loads(data)
    except Exception:
        items = []
        for line in data.splitlines():
            try: items.append(json.loads(line))
            except Exception: pass
    if not rows:
        print("toyhom sample keys:", list(items[0].keys()) if items else "none", flush=True)
    for o in items:
        if not isinstance(o, dict): continue
        dep = str(o.get("department", o.get("dep", "")))
        if "肿瘤" not in dep: continue
        q = str(o.get("question", o.get("instruction", ""))).strip()
        a = str(o.get("answer", o.get("output", ""))).strip()
        if q and a:
            rows.append({"text": f"{q}\n{a}"[:1500], "dep": dep})
        if len(rows) >= 80000: break
    return rows

if __name__ == "__main__":
    for name, fn in (("tcga_reports", dl_tcga), ("toyhom_oncology", dl_toyhom)):
        t0 = time.time()
        try:
            rows = fn()
            n = write_jsonl(name, rows) if rows else 0
            MANIFEST[name] = {"rows": n, "sec": round(time.time() - t0, 1), "ok": n > 0}
            print(f"[OK] {name}: {n} rows ({MANIFEST[name]['sec']}s)", flush=True)
        except Exception as e:
            MANIFEST[name] = {"ok": False, "err": str(e)[:300]}
            print(f"[FAIL] {name}: {e}", flush=True)
        json.dump(MANIFEST, open(os.path.join(ROOT, "manifest_final.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("FINAL-DONE", json.dumps(MANIFEST, ensure_ascii=False), flush=True)
