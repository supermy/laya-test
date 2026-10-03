#!/usr/bin/env bash
# ============================================================================
# Laya 垃圾短信微调 — 一键脚本(本地 GPU / AutoDL / Colab 通用)
#
# 适配 RTX 50 系(Blackwell sm_120):torch<2.7 会自动重装 cu128 构建并真跑 kernel 验证
# 16GB 显存:322M 全参 bf16 + 梯度检查点约用 7-9GB,默认参数直接可跑;OOM 则 MB=4
#
# 前提: 本目录(finetune/)连同 data/sms-*.jsonl 一起拷到训练机;Linux/WSL2(Windows 推荐 WSL2+CUDA 透传)
# 数据: hrwhisper/SpamMessage 80万条中文标注短信(已本地准备: 17万训练/0.9万测试)
#
# 用法:
#   cd finetune && bash run_cloud.sh            # 默认 4 epoch(5060 约 2-4h)
#   QUICK=1 bash run_cloud.sh                   # 快速冒烟: 2万条子集 1 epoch(约 20-30 分钟)
#   EPOCHS=6 bash run_cloud.sh                  # 自定义轮数
#   MB=4 bash run_cloud.sh                      # 显存不足时缩 micro-batch
#
# 产出: laya-sms-ft/(HF checkpoint) + laya-sms-int8/(端侧 ONNX) + metrics.json
# 拷回手机: scp/局域网 rsync laya-sms-results.tar.gz -> 手机解压,
#          rsync -av laya-sms-int8/ /sdcard/models/laya-sms-int8/
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"  # 国内拉 HF 模型
export USE_TF=0                                            # laya 官方提醒: TF 在场会死锁模型构建
QUICK="${QUICK:-0}"
EPOCHS="${EPOCHS:-4}"

echo "=== [0/5] 环境检查 ==="
verdict=$(python3 - <<'EOF'
def need(msg):
    print("INSTALL"); print(msg); raise SystemExit
try:
    import torch
except ImportError:
    need("torch 未安装")
import re
cap = torch.cuda.get_device_capability(0) if torch.cuda.is_available() else None
if cap is None:
    need("CUDA 不可用(驱动/WSL 透传问题)")
v = torch.__version__.split("+")[0]
major, minor = (int(x) for x in re.match(r"(\d+)\.(\d+)", v).groups())
cuda_tag = (torch.version.cuda or "").split(".")
if cap[0] >= 12:  # RTX 50 系 Blackwell sm_120:必须 torch>=2.7 的 cu128 构建
    if not (major > 2 or (major == 2 and minor >= 7)) or not cuda_tag or int(cuda_tag[0]) < 12:
        need(f"Blackwell 需 torch>=2.7+cu128,当前 {torch.__version__}")
try:  # 真跑一个 kernel,防"能 import 不能算"
    x = torch.randn(256, 256, device="cuda")
    assert float((x @ x).sum()) == float((x @ x).sum())
except Exception as e:
    need(f"CUDA kernel 执行失败: {e}")
p = torch.cuda.get_device_properties(0)
print("OK"); print(f"GPU: {p.name} sm_{cap[0]}{cap[1]} {p.total_memory/1e9:.0f}GB | torch {torch.__version__} (cuda {torch.version.cuda})")
EOF
)
if [ "$(echo "$verdict" | head -1)" = "INSTALL" ]; then
  echo "$verdict" | tail -1
  echo "--- 安装/升级 torch(cu128 构建,约 2.5GB,数分钟)... ---"
  pip install -q torch --index-url https://download.pytorch.org/whl/cu128
else
  echo "$verdict" | tail -1
fi
pip install -q "laya==0.3.4" "transformers>=4.48.0" safetensors huggingface_hub accelerate onnxruntime 2>&1 | tail -1 || true
T="${TASK:-sms}"
python3 - <<EOF
# API 冒烟: 训练脚本依赖的内部接口必须在(0.3.4 已核对,防 pip 升级漂移)
from laya.agent import _fix_tokenizer_config
from laya.common import build_sequence, render_options, build_model, proper_reward, QTYPES
import laya
print(f"laya {laya.__version__} API OK | classes 数据就绪")
import json
meta = json.load(open("data/${T}-classes.json", encoding="utf-8"))
n = sum(1 for _ in open("data/${T}-train.jsonl", encoding="utf-8"))
print(f"task=${T} classes={meta['classes']} train={n}")
EOF

if [ "$QUICK" = "1" ]; then
  echo "=== 冒烟模式: 截取 2 千条子集 1 epoch ==="
  head -2000 data/${T}-train.jsonl > data/_quick-train.jsonl
  cp data/${T}-classes.json data/_quick-classes.json 2>/dev/null || true
  DATA_ARGS="--data-dir data --prefix _quick"
  TRAIN_EXTRA="--epochs 1"
else
  DATA_ARGS="--data-dir data --prefix ${T}"
  TRAIN_EXTRA="--epochs $EPOCHS"
fi

echo "=== [1/5] RLCD 训练(${T} 任务, multilingual 322M 全参, bf16) ==="
python3 train_sms.py --task "${T}" $DATA_ARGS $TRAIN_EXTRA --output "laya-${T}-ft" --bf16 --micro-batch "${MB:-8}" 2>&1 | tee train-${T}.log

echo "=== [2/5] 测试集评测 ==="
python3 evaluate.py --model "laya-${T}-ft" --data "data/${T}-test.jsonl" --classes "data/${T}-classes.json" --task "${T}" --out "metrics-${T}.json"

echo "=== [3/5] 导出 ONNX + int8 ==="
python3 export_onnx.py --ckpt "laya-${T}-ft" --data "data/${T}-test.jsonl" --out "laya-${T}-int8" --model-name "laya-${T}" --seq-len "${SEQ_LEN:-160}"

echo "=== [4/5] 打包 ==="
tar czf "laya-${T}-results.tar.gz" "laya-${T}-int8" "metrics-${T}.json" "train-${T}.log"
ls -la "laya-${T}-results.tar.gz" "laya-${T}-int8/"

echo "=== [5/5] 完成 ==="
cat "metrics-${T}.json"
echo "拷回手机: scp my@192.168.0.168:$(pwd)/laya-${T}-results.tar.gz . && tar xzf laya-${T}-results.tar.gz"
