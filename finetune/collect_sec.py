#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""网络安全语料采集(防御性分诊用途;纯 CPU):
1) NVD CVE 2.0 API:漏洞描述(美政府作品,公有领域)→ 攻击威胁/漏洞知识
2) MITRE ATT&CK enterprise STIX(codeload tarball,绕 GitHub API 限流):战术技术描述 → 攻击威胁
3) HF ealvaradob/phishing-dataset:钓鱼文本 → 钓鱼欺诈
产出 raw/sec_cve.jsonl / sec_attack.jsonl / sec_phish.jsonl
"""
import json, os, tarfile, time, urllib.request, urllib.parse

RAW = "/data/ai/cancer/raw"
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"; os.environ["HF_HOME"] = "/data/ai/hf-cache"

def get(url, timeout=90, headers=None):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": "sec-ds/1.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()

def write(name, rows):
    n = 0
    with open(os.path.join(RAW, name), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n"); n += 1
    print(f"[OK] {name}: {n}", flush=True)
    return n

# ---------- 1. NVD CVE(120 天窗 × 多段;无 key 单窗限 2000) ----------
def nvd():
    rows = []
    import datetime
    end = datetime.datetime.now(datetime.UTC)
    win = datetime.timedelta(days=120)
    total_target = 16000
    start_t = end - win * 15  # 近 5 年
    t0 = start_t
    iso = lambda t: t.strftime("%Y-%m-%dT%H:%M:%S.000")
    while t0 < end and len(rows) < total_target:
        t1 = min(t0 + win, end)
        start = 0
        while len(rows) < total_target:
            url = (f"https://services.nvd.nist.gov/rest/json/cves/2.0?pubStartDate={iso(t0)}&pubEndDate={iso(t1)}"
                   f"&resultsPerPage=2000&startIndex={start}")
            try:
                d = json.loads(get(url, timeout=120))
            except Exception as e:
                print(f"nvd {iso(t0)[:10]} idx{start}: {e}", flush=True); break
            for v in d.get("vulnerabilities", []):
                c = v.get("cve", {})
                desc = next((x["value"] for x in c.get("descriptions", []) if x.get("lang") == "en"), "")
                cvss = 0.0
                m = c.get("metrics", {}).get("cvssMetricV31") or c.get("metrics", {}).get("cvssMetricV2") or []
                if m: cvss = m[0].get("cvssData", {}).get("baseScore", 0.0) or 0.0
                if len(desc) > 60:
                    rows.append({"text": desc[:1200], "cvss": cvss, "cve": c.get("id", "")})
            total = d.get("totalResults", 0)
            start += 2000
            if start >= total: break
            time.sleep(6)
        t0 = t1
        time.sleep(6)
    return rows

# ---------- 2. MITRE ATT&CK enterprise(codeload tarball) ----------
def attack():
    p = os.path.join(RAW, "_attack.tar.gz")
    if not os.path.exists(p):
        open(p, "wb").write(get("https://codeload.github.com/mitre-attack/attack-stix-data/tar.gz/refs/heads/master", timeout=180))
    rows = []
    with tarfile.open(p, "r:gz") as tf:
        for m in tf.getmembers():
            if m.name.endswith("enterprise-attack.json"):
                d = json.load(tf.extractfile(m))
                break
        else:
            return rows
    for o in d.get("objects", []):
        if o.get("type") != "attack-pattern": continue
        desc = o.get("description", "")
        name = o.get("name", "")
        if len(desc) > 80:
            rows.append({"text": (name + "\n" + desc)[:1500], "tid": o.get("external_references", [{}])[0].get("external_id", "")})
    return rows

# ---------- 3. HF 钓鱼数据集 ----------
def phish():
    from huggingface_hub import hf_hub_download, list_repo_files
    import csv as _csv, io
    _csv.field_size_limit(20_000_000)
    rows = []
    files = list(list_repo_files("ealvaradob/phishing-dataset", repo_type="dataset"))
    f = "texts.json" if "texts.json" in files else next((f for f in files if f.endswith(".json")), None)
    print("phish file:", f, flush=True)
    loc = hf_hub_download("ealvaradob/phishing-dataset", f, repo_type="dataset")
    data = open(loc, encoding="utf-8", errors="ignore").read()
    try:
        items = json.loads(data)
        if isinstance(items, dict): items = items.get("data", [items])
    except Exception:
        items = [json.loads(l) for l in data.splitlines() if l.strip()]
    for r in items:
        if not isinstance(r, dict): continue
        t = next((str(r[k]) for k in r if k.lower() in ("text", "body", "email_text", "message", "email", "content")), "")
        lab = next((str(r[k]) for k in r if k.lower() in ("label", "type", "class")), "")
        if t and len(t) > 60 and "phish" in lab.lower():
            rows.append({"text": t[:1000], "lab": lab})
        if len(rows) >= 4000: break
    return rows

def phish():
    from huggingface_hub import hf_hub_download
    import csv as _csv, io
    _csv.field_size_limit(20_000_000)
    rows = []
    loc = hf_hub_download("zefang-liu/phishing-email-dataset", "Phishing_Email.csv", repo_type="dataset")
    data = open(loc, encoding="utf-8", errors="ignore").read()
    for r in _csv.DictReader(io.StringIO(data)):
        t = next((r[k] for k in r if k and "text" in k.lower()), "")
        lab = next((r[k] for k in r if k and ("type" in k.lower() or "label" in k.lower())), "")
        if t and len(t) > 60 and "phish" in str(lab).lower():
            rows.append({"text": t[:1000], "lab": str(lab)})
        if len(rows) >= 4500: break
    return rows

if __name__ == "__main__":
    import sys
    only = sys.argv[1:] or None
    for name, fn in (("sec_cve.jsonl", nvd), ("sec_attack.jsonl", attack), ("sec_phish.jsonl", phish)):
        if only and name not in only: continue
        try:
            write(name, fn())
        except Exception as e:
            print(f"[FAIL] {name}: {e}", flush=True)
    print("SEC-COLLECT-DONE", flush=True)
