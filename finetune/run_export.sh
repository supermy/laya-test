#!/usr/bin/env bash
# ============================================================================
# Laya multi 模型缩序列重导出(160 token) — 在 5060 电脑/Linux 上跑,不需要训练
#
# 产出: laya-multi-160/  (int8 onnx + fp32 onnx + tokenizer + laya_config + manifest)
# 拷回手机: tar 包传回后 rsync 到 /sdcard/models/laya-multi-160/
#
# 说明: 底座是 convaiinnovations/laya-multilingual(HF 自动下载,约 1.3GB);
#       question_defs 用 questions.json(股票三问 + 短信两问,一个模型服务两个技能)
# 用法: bash run_export.sh          # 约 10-20 分钟(大头是 torch 下载 + checkpoint 下载)
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
export USE_TF=0

echo "=== [1/3] 独立 venv + 依赖(CPU torch 够用) ==="
python3 -m venv venv
source venv/bin/activate
pip install -q torch --index-url https://pypi.tuna.tsinghua.edu.cn/simple
pip install -q -i https://pypi.tuna.tsinghua.edu.cn/simple \
  onnxruntime "transformers>=4.48.0" safetensors huggingface_hub numpy
pip install -q -i https://pypi.tuna.tsinghua.edu.cn/simple --no-deps laya==0.3.4
python3 -c "import torch, laya, transformers; print('torch', torch.__version__, '| laya', laya.__version__, 'OK')"

echo "=== [2/3] 导出 [1,160] fp32 -> int8 ==="
python3 export_onnx.py \
  --ckpt convaiinnovations/laya-multilingual \
  --questions questions.json \
  --seq-len 160 \
  --model-name laya-multi \
  --out laya-multi-160

echo "=== [3/3] 打包 ==="
tar czf laya-multi-160.tar.gz laya-multi-160
ls -la laya-multi-160.tar.gz laya-multi-160/
echo "完成。把 laya-multi-160.tar.gz 传回手机,解压到 /sdcard/models/ 即可。"
