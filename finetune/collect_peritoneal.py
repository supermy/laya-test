#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""腹膜癌专用语料采集(PC,纯 CPU):
1) 存量池关键词过滤(zh: 腹膜/腹水/腹腔;en: peritoneal)
2) Europe PMC 开放获取摘要 + ClinicalTrials.gov v2 试验摘要(en 增量)
产出 raw/peritoneal_{zh,en}.jsonl + raw/pool_peritoneal.jsonl(en 翻译池)
"""
import json, os, time, urllib.request, urllib.parse

RAW = "/data/ai/cancer/raw"
ZH_KW = ("腹膜", "腹水", "腹腔", "腹膜癌", "腹膜转移")
EN_KW = ("peritoneal",)

def load(name):
    p = os.path.join(RAW, name)
    if not os.path.exists(p):
        print(f"[miss] {name}"); return []
    return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]

def get(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "pca-ds/1.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()

def write(name, rows):
    n = 0
    with open(os.path.join(RAW, name), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n"); n += 1
    return n

zh_rows, en_rows = [], []
# ---------- 1. 存量池过滤 ----------
for name in ("cmb_exam.jsonl", "toyhom_oncology.jsonl", "medical_book_zh.jsonl", "cblue.jsonl"):
    hits = [r for r in load(name) if any(k in r.get("text", "") for k in ZH_KW)]
    zh_rows += [{"text": r["text"][:1500], "src": name.split(".")[0]} for r in hits]
    print(f"[zh] {name}: {len(hits)}")
for name, kindkey in (("tcga_reports.jsonl", None), ("medhallu.jsonl", "row_type")):
    hits = [r for r in load(name) if any(k in r.get("text", "").lower() for k in EN_KW) or (kindkey and any(k in str(r.get(kindkey, "")).lower() for k in EN_KW))]
    en_rows += [{"text": r["text"][:1200], "src": name.split(".")[0]} for r in hits]
    print(f"[en] {name}: {len(hits)}")

# ---------- 2. Europe PMC 开放获取摘要 ----------
def europepmc():
    q = 'peritoneal carcinomatosis OR "peritoneal metastasis" OR "peritoneal mesothelioma" OR "pseudomyxoma peritonei" OR "gastric cancer peritoneal"'
    url = ("https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=" +
           urllib.parse.quote(f"({q}) AND OPEN_ACCESS:y") +
           "&format=json&pageSize=1000&resultType=core")
    d = json.loads(get(url, timeout=90))
    out = []
    for r in d.get("resultList", {}).get("result", []):
        ab = (r.get("abstractText") or "").replace("<h4>", "\n").replace("</h4>", ":\n").replace("<p>", "").replace("</p>", "")
        t = ((r.get("title") or "") + "\n" + ab).strip()
        if len(t) > 120:
            out.append({"text": t[:2000], "pmid": r.get("pmid", ""), "year": r.get("pubYear", ""), "src": "europepmc"})
    return out

# ---------- 3. ClinicalTrials.gov v2 ----------
def trials():
    out, token = [], None
    for cond in ("peritoneal carcinomatosis", "peritoneal mesothelioma", "pseudomyxoma peritonei"):
        for _ in range(3):
            url = ("https://clinicaltrials.gov/api/v2/studies?query.cond=" + urllib.parse.quote(cond) +
                   "&fields=BriefTitle,BriefSummary,Condition&pageSize=200" + (f"&pageToken={token}" if token else ""))
            try:
                d = json.loads(get(url, timeout=60)); break
            except Exception as e:
                print(f"trials retry {cond}: {e}", flush=True); time.sleep(5); d = None
        if not d: continue
        for s in d.get("studies", []):
            p = s.get("protocolSection", {})
            bt = p.get("identificationModule", {}).get("briefTitle", "")
            bs = p.get("descriptionModule", {}).get("briefSummary", "")
            t = (bt + "\n" + bs).strip()
            if len(t) > 100:
                out.append({"text": t[:1500], "src": "clinicaltrials"})
            token = d.get("nextPageToken")
        token = d.get("nextPageToken")
        if not token: continue
    return out

try:
    ep = europepmc(); print(f"[en] europepmc: {len(ep)}")
except Exception as e:
    ep = []; print(f"[FAIL] europepmc: {e}")
try:
    tr = trials(); print(f"[en] clinicaltrials: {len(tr)}")
except Exception as e:
    tr = []; print(f"[FAIL] clinicaltrials: {e}")
en_rows += ep + tr

n1 = write("peritoneal_zh.jsonl", zh_rows)
n2 = write("peritoneal_en.jsonl", en_rows)
print(f"zh={n1} en={n2}", flush=True)
