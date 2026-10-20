#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cancer 业务数据源下载(5060 Ti PC,/data/ai/cancer/)
每个源独立 try,失败不阻塞其他源;产物 raw/<name>.jsonl + manifest.json
许可边界:MIMIC 原始数据不入本流程(用户提供本地副本,另走 convert);NCCN 不使用(版权)。
"""
import json, os, sys, time, hashlib
import urllib.request, urllib.parse

ROOT = "/data/ai/cancer"
RAW = os.path.join(ROOT, "raw")
os.makedirs(RAW, exist_ok=True)
HF = "https://hf-mirror.com"
os.environ.setdefault("HF_ENDPOINT", HF)
MANIFEST = {}

def write_jsonl(name, rows):
    p = os.path.join(RAW, f"{name}.jsonl")
    n = 0
    with open(p, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n"); n += 1
    return n

def get(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": "cancer-ds/1.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()

def gh_api(path):
    return json.loads(get("https://api.github.com" + path))

def hf_api(path):
    return json.loads(get(HF + path))

# ---------- 1. Toyhom 中文医疗对话·肿瘤科(GitHub,75,553 条) ----------
def dl_toyhom():
    tree = gh_api("/repos/Toyhom/Chinese-medical-dialogue-data/contents/")
    rows = []
    for d in tree:
        if d["type"] != "dir" or "肿瘤" not in d["name"] and "Oncology" not in d["name"]:
            continue
        files = gh_api(f"/repos/Toyhom/Chinese-medical-dialogue-data/contents/{urllib.parse.quote(d['path'])}")
        for f in files:
            if f["name"].endswith(".csv"):
                # csv 列:科室,title,question,answer(gbk/utf8 兼容)
                data = get(f["download_url"]).decode("utf-8", "ignore")
                import csv, io
                for r in csv.DictReader(io.StringIO(data)):
                    q = (r.get("question") or r.get("\ufeffquestion") or "").strip()
                    a = (r.get("answer") or "").strip()
                    if q and a:
                        rows.append({"text": f"{q}\n{a}", "dep": r.get("department", "肿瘤科")})
    return rows[:80000]

# ---------- 2. CMB-Exam(FreedomIntelligence,HF) ----------
def dl_cmb():
    from datasets import load_dataset
    ds = load_dataset("FreedomIntelligence/CMB", "exam", split="test", trust_remote_code=False)
    rows = []
    for r in ds:
        q = (r.get("question") or "").strip()
        if not q: continue
        rows.append({"text": q, "answer": r.get("answer", ""), "exam_type": r.get("exam_type", ""),
                     "exam_class": r.get("exam_class", "")})  # exam_class 含 肿瘤学 过滤用
    return rows

# ---------- 3. shibing624/medical(HF;肿瘤科子集与 Toyhom 同源,抽检去重) ----------
def dl_shibing():
    from datasets import load_dataset
    ds = load_dataset("shibing624/medical", split="train")
    rows = []
    for r in ds:
        t = " ".join(str(r.get(k, "")) for k in ("question", "answer") if r.get(k)).strip()
        if "肿瘤" in str(r.get("department", "")) or "癌" in t or "肿瘤" in t:
            rows.append({"text": t})
    return rows[:20000]  # 上限,够用且防倾斜

# ---------- 4. CBLUE(CHIP-CTC 临床试验筛选标准/CHIP-CDN 诊断标准化/KUAKE) ----------
def dl_cblue():
    # HF 社区镜像搜索
    hits = hf_api("/api/datasets?search=CBLUE&limit=10")
    ids = [h["id"] for h in hits]
    rows = []
    from datasets import load_dataset
    for did in ids:
        try:
            ds = load_dataset(did, split="train", trust_remote_code=False)
            for r in list(ds)[:8000]:
                t = " ".join(str(v) for k, v in r.items() if isinstance(v, str))
                if t.strip():
                    rows.append({"text": t[:600], "src": did})
            if len(rows) > 20000: break
        except Exception as e:
            print(f"cblue: skip {did}: {e}", flush=True)
    return rows

# ---------- 5. MedHallu / Med-HALT(医疗幻觉,取癌症子集) ----------
def dl_medhallu():
    hits = hf_api("/api/datasets?search=MedHallu&limit=10")
    rows = []
    from datasets import load_dataset
    for h in hits:
        try:
            ds = load_dataset(h["id"], split="train")
            for r in ds:
                t = " ".join(str(v) for v in (r.get("question"), r.get("correct_answer"), r.get("hallucinated_answer")) if v)
                if "cancer" in t.lower() or "tumor" in t.lower() or "carcinoma" in t.lower() or "oncolog" in t.lower():
                    rows.append({"text": t[:800], "hallu": r.get("label", ""), "src": h["id"]})
            if rows: break
        except Exception as e:
            print(f"medhallu: skip {h['id']}: {e}", flush=True)
    return rows

def dl_medhalt():
    # GitHub big-hci/medhalt:数据目录 raw jsonl
    try:
        tree = gh_api("/repos/big-hci/medhalt/git/trees/main?recursive=1")
        rows = []
        for f in tree.get("tree", []):
            if f["path"].endswith(".jsonl") and "cancer" in f["path"].lower():
                data = get(f"https://raw.githubusercontent.com/big-hci/medhalt/main/{urllib.parse.quote(f['path'])}").decode("utf-8", "ignore")
                for line in data.splitlines():
                    try:
                        o = json.loads(line)
                        t = " ".join(str(o.get(k, "")) for k in ("query", "statement", "text", "premise") if o.get(k))
                        if t.strip(): rows.append({"text": t[:800], "src": "medhalt"})
                    except Exception: pass
        return rows
    except Exception as e:
        print(f"medhalt: {e}", flush=True); return []

# ---------- 6. TCGA-Reports(Kefeli 2024,病理报告) ----------
def dl_tcga():
    res = gh_api("/search/repositories?q=TCGA-Reports&per_page=5")
    for it in res.get("items", []):
        full = it["full_name"]
        try:
            tree = gh_api(f"/repos/{full}/git/trees/main?recursive=1")
        except Exception:
            try: tree = gh_api(f"/repos/{full}/git/trees/master?recursive=1")
            except Exception: continue
        rows, got = [], 0
        for f in tree.get("tree", []):
            if f["path"].lower().endswith((".csv", ".json", ".jsonl", ".tsv")) and f.get("size", 0) < 200_000_000:
                data = get(f"https://raw.githubusercontent.com/{full}/main/{urllib.parse.quote(f['path'])}").decode("utf-8", "ignore")
                if f["path"].endswith((".json", ".jsonl")):
                    for line in data.splitlines():
                        try:
                            o = json.loads(line)
                            t = " ".join(str(o.get(k, "")) for k in ("text", "report", "pathology") if o.get(k))
                            if t.strip(): rows.append({"text": t[:1500], "src": full})
                        except Exception: pass
                got += 1
                if got >= 5 or len(rows) > 12000: break
        if rows:
            return rows
    return []

# ---------- 7. NCI PDQ 患者版(公有领域) ----------
def dl_pdq():
    # PDQ 患者版列表页 + 每篇正文;失败则留空待手工
    rows = []
    try:
        import re
        html = get("https://www.cancer.gov/publications/dictionaries/cancer-terms").decode("utf-8", "ignore")
    except Exception:
        pass
    # 官方导出接口(尽量,失败不阻塞)
    try:
        data = get("https://api.cancer.gov/v1/summary/patient?size=500", timeout=60)
        js = json.loads(data)
        for r in js.get("results", []):
            t = r.get("summary", {}).get("text", "") or r.get("description", "")
            if t.strip():
                rows.append({"text": re.sub(r"<[^>]+>", " ", t)[:2000], "title": r.get("name", ""), "src": "nci-pdq"})
    except Exception as e:
        print(f"pdq: {e}", flush=True)
    return rows

SOURCES = [
    ("toyhom_oncology", dl_toyhom),
    ("cmb_exam", dl_cmb),
    ("shibing_medical", dl_shibing),
    ("cblue", dl_cblue),
    ("medhallu", dl_medhallu),
    ("medhalt", dl_medhalt),
    ("tcga_reports", dl_tcga),
    ("nci_pdq", dl_pdq),
]

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
        with open(os.path.join(ROOT, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(MANIFEST, f, ensure_ascii=False, indent=2)
    print("DOWNLOAD-DONE", json.dumps(MANIFEST, ensure_ascii=False), flush=True)
