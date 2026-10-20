#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sec 数据增强采集(在 collect_sec.py 基础上新增真实源;防御性告警分诊用途):
新增 CISA KEV(已知被利用漏洞,美国政府作品/公有领域) → 高严重度攻击威胁 + 必须人工研判。
原 NVD/ATT&CK/phishing 复用 raw/ 里已有文件,不重复下载。
产出 raw/sec2_kev.jsonl。
用法: python collect_sec2.py
"""
import json, os, urllib.request

RAW = "/data/ai/cancer/raw"
KEV_URL = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"

def get(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": "sec-ds/2.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()

def kev():
    d = json.loads(get(KEV_URL))
    rows = []
    for v in d.get("vulnerabilities", []):
        name = v.get("vulnerabilityName", "")
        desc = v.get("shortDescription", "")
        vendor = v.get("vendorProject", "")
        product = v.get("product", "")
        ransomware = str(v.get("knownRansomwareCampaignUse", "Unknown")).lower() == "known"
        text = f"{name}. {desc} 受影响产品:{vendor} {product}。"
        if ransomware:
            text += "该漏洞已被勒索软件组织在野利用。"
        text += f"处置要求:{v.get('requiredAction', '')} 截止:{v.get('dueDate', '')}"
        if len(text) > 40:
            rows.append({"text": text[:1200], "cve": v.get("cveID", ""), "ransomware": ransomware})
    return rows

def write(name, rows):
    with open(os.path.join(RAW, name), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"[OK] {name}: {len(rows)}", flush=True)

if __name__ == "__main__":
    os.makedirs(RAW, exist_ok=True)
    try:
        write("sec2_kev.jsonl", kev())
    except Exception as e:
        print(f"[FAIL] kev: {e}", flush=True)
    print("SEC2-COLLECT-DONE", flush=True)