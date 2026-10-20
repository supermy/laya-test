#!/usr/bin/env bash
# ============================================================================
# cancer 训练完成后的全自动接续链(5060 PC,由 watchdog 触发,无需人工)
# 门禁 → 迁移/合并训练仓 → cancer 评测+导出 → 腹膜癌翻译 → 组装 → 两阶段训练 → 导出
# 日志: /data/ai/logs-cancer/post.log;末行 POST-ALL-DONE 表示全链成功
# ============================================================================
set -uo pipefail
LOG=/data/ai/logs-cancer/post.log
exec >> "$LOG" 2>&1
echo "=== post_cancer start $(date) ==="

# [1] 空间门禁:源 20G 迁往 /data,迁移后余量须 ≥5G
SRC=$(du -sBG /home/my/finetune 2>/dev/null | cut -f1); SRC=${SRC%G}
AVAIL=$(df --output=avail -BG /data/ai | tail -1 | tr -dc 0-9); AVAIL=${AVAIL%G}
MARGIN=$((AVAIL - SRC))
echo "[gate] src=${SRC}G avail=${AVAIL}G margin=${MARGIN}G"
if [ -z "$SRC" ] || [ "$MARGIN" -lt 5 ]; then echo "GATE-FAIL(余量不足或源缺失),中止"; exit 1; fi

# [2] 迁移:finetune-cancer 改名 → finetune;/home/my/finetune 并入(腾 / 分区)
mv /data/ai/finetune-cancer /data/ai/finetune
cp -a /home/my/finetune/. /data/ai/finetune/ && echo "[migrate] copy done"
PY=/data/ai/finetune/venv/bin/python
$PY -c "import torch, transformers; print('[venv] ok', torch.__version__)" || { echo "VENV-BROKEN,保留 /home/my/finetune 不删"; exit 1; }
rm -rf /home/my/finetune && echo "[migrate] /home/my/finetune removed(腾出 / 分区)"
cd /data/ai/finetune
export HF_HOME=/data/ai/hf-cache HF_ENDPOINT=https://hf-mirror.com

# [3] cancer 评测 + 导出 int8
$PY evaluate.py --task cancer --model /data/ai/laya-cancer-ft \
  --data data/cancer-test.jsonl --classes data/cancer-classes.json \
  --out /data/ai/logs-cancer/metrics-cancer.json || echo "[warn] cancer evaluate 失败,继续导出"
$PY export_onnx.py --ckpt /data/ai/laya-cancer-ft --data data/cancer-test.jsonl \
  --out /data/ai/laya-cancer-int8 --model-name laya-cancer --seq-len 160 || { echo "CANCER-EXPORT-FAIL"; exit 1; }

# [4] 腹膜癌 en 翻译池 → 教师翻译(GPU 已空闲)
$PY /data/ai/cancer/build_pool_peritoneal.py
pkill -f "[l]lama-server" 2>/dev/null; sleep 3
nohup /data/ai/prisml-llama.cpp/build/bin/llama-server \
  -m /mnt/shared_data/models/Qwen3.6-35B-A3B-UD-IQ3_XXS.gguf \
  --port 8080 --host 0.0.0.0 -c 8192 -ngl 99 --jinja > /data/ai/logs-cancer/llama2.log 2>&1 &
for i in $(seq 1 90); do sleep 2; curl -sm3 http://127.0.0.1:8080/health 2>/dev/null | grep -q true && break; done
$PY /data/ai/cancer/translate_cancer.py --files pool_peritoneal || echo "[warn] 翻译有失败,继续(断点可续)"
pkill -f "[l]lama-server" 2>/dev/null; sleep 3

# [5] 腹膜癌组装 + 两阶段训练(以 laya-cancer-ft 为底座)
$PY convert_peritoneal.py
$PY train_sms.py --task peritoneal --model /data/ai/laya-cancer-ft \
  --data-dir data --prefix peritoneal --output /data/ai/laya-peritoneal-ft \
  --bf16 --epochs 2 || { echo "PERITONEAL-TRAIN-FAIL"; exit 1; }

# [6] 腹膜癌评测 + 导出
$PY evaluate.py --task peritoneal --model /data/ai/laya-peritoneal-ft \
  --data data/peritoneal-test.jsonl --classes data/peritoneal-classes.json \
  --out /data/ai/logs-cancer/metrics-peritoneal.json || echo "[warn] peritoneal evaluate 失败,继续导出"
$PY export_onnx.py --ckpt /data/ai/laya-peritoneal-ft --data data/peritoneal-test.jsonl \
  --out /data/ai/laya-peritoneal-int8 --model-name laya-peritoneal --seq-len 160 || { echo "PCA-EXPORT-FAIL"; exit 1; }

echo "=== POST-ALL-DONE $(date) ==="
