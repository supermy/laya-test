#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cancer 数据源下载 v2:避开 raw.githubusercontent(不通),走 GitHub API base64 + hf_hub_download(hf-mirror)。"""
import json, os, sys, time, base64, urllib.request, urllib.parse

ROOT = "/data/ai/cancer"; RAW = os.path.join(ROOT, "raw")
os.makedirs(RAW, exist_ok=True)
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
os.environ["HF_HOME"] = "/data/ai/hf-cache"  # /home 在 / 分区已 100% 满,缓存必须走 /data
HF = "https://hf-mirror.com"
MANIFEST = {}

def write_jsonl(name, rows):
    n = 0
    with open(os.path.join(RAW, f"{name}.jsonl"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n"); n += 1
    return n

def get(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "cancer-ds/2.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()

def gh_api(path):
    return json.loads(get("https://api.github.com" + path))

def gh_file(full_repo, fpath, ref="main"):
    """GitHub API contents → base64(绕过 raw.githubusercontent)"""
    o = gh_api(f"/repos/{full_repo}/contents/{urllib.parse.quote(fpath)}?ref={ref}")
    return base64.b64decode(o["content"])

def hf_tree(repo):
    return json.loads(get(f"{HF}/api/datasets/{repo}/tree/main?recursive=true&limit=1000"))

def hf_dl(repo, fpath):
    from huggingface_hub import hf_hub_download
    return hf_hub_download(repo_id=repo, filename=fpath, repo_type="dataset")

def rows_from_jsonish(path, text_keys, cap=20000):
    """jsonl/json/csv → rows(text 拼接)"""
    rows = []
    if path.endswith((".jsonl", ".json")):
        data = open(path, encoding="utf-8", errors="ignore").read()
        try:
            obj = json.loads(data)
            items = obj if isinstance(obj, list) else obj.get("data", obj.get("results", [obj]))
            if isinstance(items, dict): items = [items]
        except Exception:
            items = []
            for line in data.splitlines():
                try: items.append(json.loads(line))
                except Exception: pass
        for o in items:
            if not isinstance(o, dict): continue
            t = "\n".join(str(o[k]) for k in text_keys if o.get(k))
            if t.strip(): rows.append({**{"text": t[:1500]}, **{k: o[k] for k in o if k not in text_keys and isinstance(o[k], (str, int))}})
            if len(rows) >= cap: break
    elif path.endswith(".csv"):
        import csv, io
        data = open(path, encoding="utf-8", errors="ignore").read()
        for r in csv.DictReader(io.StringIO(data)):
            t = "\n".join((r.get(k) or "").strip() for k in ("question", "answer", "title", "text") if r.get(k))
            if t.strip(): rows.append({"text": t[:1500], "dep": r.get("department", "")})
            if len(rows) >= cap: break
    return rows

def cand_files(tree, exts=(".json", ".jsonl", ".csv"), max_mb=300, must=None):
    out = []
    for f in tree:
        p = f.get("path", "")
        if f.get("type") != "file" or f.get("size", 0) > max_mb * 1e6: continue
        if p.endswith(exts) and (must is None or any(m in p.lower() for m in must)):
            out.append(p)
    return sorted(out)

def gh_tree(repo):
    br = json.loads(get(f"https://api.github.com/repos/{repo}")).get("default_branch", "master")
    return br, gh_api(f"/repos/{repo}/git/trees/{br}?recursive=1")

# ---------- 1. Toyhom 肿瘤科(GitHub API base64;默认分支自适应) ----------
def dl_toyhom():
    br, tree = gh_tree("Toyhom/Chinese-medical-dialogue-data")
    rows = []
    for f in tree.get("tree", []):
        if f["path"].endswith(".csv") and ("Oncology" in f["path"] or "肿瘤" in f["path"]):
            blob = gh_file("Toyhom/Chinese-medical-dialogue-data", f["path"], br)
            tmp = os.path.join(RAW, "_toyhom.csv")
            open(tmp, "wb").write(blob)
            rows += rows_from_jsonish(tmp, None)
    return rows

# ---------- 2. CMB-Exam(HF FreedomIntelligence/CMB) ----------
def dl_cmb():
    tree = hf_tree("FreedomIntelligence/CMB")
    rows = []
    for p in cand_files(tree, must=("exam",))[:4]:
        loc = hf_dl("FreedomIntelligence/CMB", p)
        rows += rows_from_jsonish(loc, ("question",), cap=30000)
    return rows

# ---------- 3. shibing624/medical(对话;肿瘤过滤放 convert) ----------
def dl_shibing():
    tree = hf_tree("shibing624/medical")
    rows = []
    for p in cand_files(tree, must=("dialogue", "train"))[:3]:
        loc = hf_dl("shibing624/medical", p)
        rows += rows_from_jsonish(loc, ("question", "answer"), cap=20000)
    return rows

# ---------- 4. CBLUE(HF 搜镜像,取前两个可用) ----------
def dl_cblue():
    hits = json.loads(get(f"{HF}/api/datasets?search=CBLUE&limit=10"))
    rows = []
    for h in hits:
        try:
            tree = hf_tree(h["id"])
            for p in cand_files(tree)[:6]:
                loc = hf_dl(h["id"], p)
                rows += rows_from_jsonish(loc, ("text", "sentence", "question"), cap=8000)
            if len(rows) > 12000: break
        except Exception as e:
            print(f"cblue skip {h['id']}: {e}", flush=True)
    return rows

# ---------- 5. MedHallu(HF,癌症子集过滤) ----------
def dl_medhallu():
    hits = json.loads(get(f"{HF}/api/datasets?search=MedHallu&limit=10"))
    rows = []
    for h in hits:
        try:
            tree = hf_tree(h["id"])
            for p in cand_files(tree)[:4]:
                loc = hf_dl(h["id"], p)
                for r in rows_from_jsonish(loc, ("question", "correct_answer", "hallucinated_answer"), cap=20000):
                    t = r["text"].lower()
                    if any(k in t for k in ("cancer", "tumor", "carcinoma", "oncolog", "tumour")):
                        rows.append(r)
            if rows: break
        except Exception as e:
            print(f"medhallu skip {h['id']}: {e}", flush=True)
    return rows

# ---------- 6. Med-HALT(GitHub 搜索仓库名) ----------
def dl_medhalt():
    res = gh_api("/search/repositories?q=med-halt+OR+medhalt&per_page=5")
    for it in res.get("items", []):
        full = it["full_name"]
        try:
            br, tree = gh_tree(full)
        except Exception:
            continue
        rows = []
        for f in tree.get("tree", []):
            if f["path"].endswith((".jsonl", ".json")) and f.get("size", 0) < 100_000_000:
                try:
                    blob = gh_file(full, f["path"], br)
                    tmp = os.path.join(RAW, "_medhalt.jsonl"); open(tmp, "wb").write(blob)
                    rows += rows_from_jsonish(tmp, ("query", "statement", "text", "premise", "question"), cap=8000)
                except Exception as e:
                    print(f"medhalt skip {f['path']}: {e}", flush=True)
                if len(rows) > 8000: break
        if rows: return rows
    return []

# ---------- 7. TCGA-Reports(HF 检索 → GitHub → Zenodo) ----------
def dl_tcga():
    # a) HF 搜 tcga 病理报告
    for q in ("TCGA pathology reports", "tcga-reports", "pathology reports TCGA"):
        try:
            hits = json.loads(get(f"{HF}/api/datasets?search={urllib.parse.quote(q)}&limit=10"))
        except Exception:
            continue
        for h in hits:
            try:
                tree = hf_tree(h["id"])
                for p in cand_files(tree)[:5]:
                    loc = hf_dl(h["id"], p)
                    rows = rows_from_jsonish(loc, ("text", "report", "pathology", "contents"), cap=12000)
                    if rows: print(f"tcga via HF {h['id']} {p}", flush=True); return rows
            except Exception as e:
                print(f"tcga HF skip {h['id']}: {e}", flush=True)
    # b) GitHub
    res = gh_api("/search/repositories?q=TCGA+pathology+reports&per_page=5")
    for it in res.get("items", []):
        full = it["full_name"]
        try: br, tree = gh_tree(full)
        except Exception: continue
        rows = []
        for f in tree.get("tree", []):
            if f["path"].lower().endswith((".csv", ".json", ".jsonl")) and f.get("size", 0) < 100_000_000:
                try:
                    blob = gh_file(full, f["path"], br)
                    tmp = os.path.join(RAW, "_tcga"); open(tmp, "wb").write(blob)
                    rows += rows_from_jsonish(tmp, ("text", "report", "pathology"), cap=12000)
                except Exception as e:
                    print(f"tcga skip {f['path']}: {e}", flush=True)
                if len(rows) > 6000: break
        if rows: print(f"tcga via GitHub {full}", flush=True); return rows
    return []

SOURCES = [("toyhom_oncology", dl_toyhom), ("cmb_exam", dl_cmb), ("shibing_medical", dl_shibing),
           ("cblue", dl_cblue), ("medhallu", dl_medhallu), ("medhalt", dl_medhalt), ("tcga_reports", dl_tcga)]

if __name__ == "__main__":
    only = sys.argv[1:] or None
    for name, fn in SOURCES:
        if only and name not in only: continue
        t0 = time.time()
        try:
            rows = fn()
            n = write_jsonl(name, rows) if rows else 0
            MANIFEST[name] = {"rows": n, "sec": round(time.time() - t0, 1), "ok": n > 0}
            print(f"[OK] {name}: {n} rows ({MANIFEST[name]['sec']}s)", flush=True)
        except Exception as e:
            MANIFEST[name] = {"ok": False, "err": str(e)[:300]}
            print(f"[FAIL] {name}: {e}", flush=True)
        json.dump(MANIFEST, open(os.path.join(ROOT, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("DOWNLOAD-DONE", json.dumps(MANIFEST, ensure_ascii=False), flush=True)
