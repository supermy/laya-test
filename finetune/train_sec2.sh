#!/usr/bin/env bash
# sec 微调 v2(GPU)启动器 —— 由 ×2-ft 之外独立运行;日志见调用方重定向
set -euo pipefail
cd /data/ai/finetune
export USE_TF=0
export HF_HOME=/data/ai/hf-cache
export HF_ENDPOINT=https://hf-mirror.com
exec venv/bin/python -u train_sms.py \
  --task sec --prefix sec2 --data-dir data \
  --output /data/ai/laya-sec-ft2 \
  --epochs 4 --bf16