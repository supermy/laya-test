#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""腹膜癌 en 翻译池构建:tcga 全量 + europepmc/trials 截量 → raw/pool_peritoneal.jsonl"""
import json, os

RAW = "/data/ai/cancer/raw"
CAP = {"tcga_reports": 1000, "europepmc": 400, "clinicaltrials": 100}
cnt, rows = {}, []
for r in (json.loads(l) for l in open(os.path.join(RAW, "peritoneal_en.jsonl"), encoding="utf-8") if l.strip()):
    src = r.get("src", "unk")
    if cnt.get(src, 0) >= CAP.get(src, 100):
        continue
    cnt[src] = cnt.get(src, 0) + 1
    rows.append({"text": r["text"][:1000], "src": src})
with open(os.path.join(RAW, "pool_peritoneal.jsonl"), "w", encoding="utf-8") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
print("pool_peritoneal:", len(rows), cnt)
