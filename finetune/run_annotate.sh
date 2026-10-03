#!/usr/bin/env bash
# ============================================================================
# 教师标注一站式脚本(5060 电脑/Linux) — llama.cpp CUDA + Qwen3.5-35B-A3B IQ3_XXS
#
# 产出: data/stock-labeled.jsonl (利好/利空/中性 银标签)
# 之后: python3 prepare_data.py --input data/stock-labeled.jsonl --text-col text \
#         --label-col label --normal-label 中性 --out-prefix data/stock
#       (中性当"正常类",训练脚本要求 normal 存在;倾向三分类里中性≈无倾向基类)
#
# 需要前置: NVIDIA 驱动 + CUDA 12(5060 = Blackwell 需要 cuda build >= 12.8)
# 用法: bash run_annotate.sh            # 单过标注(最快)
#       TWO_PASS=1 bash run_annotate.sh # 双过消位置偏置(时间x2,标签更干净,推荐)
#       LIMIT=3000 bash run_annotate.sh # 先标 3 千条试水
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
GGUF="Qwen3.5-35B-A3B-UD-IQ3_XXS.gguf"
MODEL_REPO="Qwen/Qwen3.5-35B-A3B-UD-IQ3_XXS-GGUF"   # 官方 UD 量化(手机上同名文件已验证可用)
PORT=8180

echo "=== [1/4] llama.cpp CUDA 二进制 ==="
if [ ! -x tools/llama-server ]; then
  mkdir -p tools
  # 官方 release 预编译(cuda bxxxx);取最新 linux x64 cuda 包
  URL=$(curl -sL https://api.github.com/repos/ggml-org/llama.cpp/releases/latest \
    | python3 -c "import json,sys; [print(a['browser_download_url']) for a in json.load(sys.stdin)['assets'] if 'bin-ubuntu-x64' in a['name'] and 'cuda' in a['name']]" | head -1)
  [ -n "$URL" ] || { echo "没找到 cuda 预编译包,请手动装 llama.cpp(需要 CUDA 12.8+)"; exit 1; }
  echo "下载 $URL"
  curl -sL "$URL" -o tools/llama.zip && unzip -o -q tools/llama.zip -d tools && chmod +x tools/llama-server
fi

echo "=== [2/4] 下载教师模型(IQ3_XXS 约 14GB,已下载则跳过) ==="
if [ ! -f "$GGUF" ]; then
  # 官方 GGUF 是分片,拉全部分片
  BASE="https://hf-mirror.com/$MODEL_REPO/resolve/main"
  for f in $(curl -sL "https://hf-mirror.com/api/models/$MODEL_REPO" | python3 -c "import json,sys; [print(s['rfilename']) for s in json.load(sys.stdin)['siblings'] if s['rfilename'].endswith('.gguf')]"); do
    [ -f "$f" ] || { echo "下载 $f"; curl -sL --retry 5 "$BASE/$f" -o "$f"; }
  done
fi

echo "=== [3/4] 起 llama-server(GPU offload 全部层) ==="
pkill -f "llama-server.*$PORT" 2>/dev/null || true
./tools/llama-server -m "$GGUF" --port $PORT -ngl 99 -c 2048 --temp 0.0 >/dev/null 2>&1 &
for i in $(seq 1 120); do
  sleep 2
  curl -s "http://127.0.0.1:$PORT/health" | grep -q '"ok": *true' && break
done
curl -s "http://127.0.0.1:$PORT/health" || { echo "server 没起来,查端口/显存"; exit 1; }

echo "=== [4/4] 标注 ==="
if [ "${TWO_PASS:-0}" = "1" ]; then TP="--two-pass"; else TP=""; fi
python3 annotate_news.py --pool data/stock-pool.jsonl --out data/stock-labeled.jsonl \
  --base "http://127.0.0.1:$PORT" ${LIMIT:+--limit $LIMIT} $TP

echo "=== 生成训练包 ==="
python3 convert_stock.py --input data/stock-labeled.jsonl --out-prefix data/stock
tar czf stock-data.tar.gz data/stock-train.jsonl data/stock-test.jsonl data/stock-classes.json
echo "完成: stock-data.tar.gz (拷回手机并入 finetune/data/, 用下面命令训练股票版)"
echo "  python3 train_sms.py --task stock --data-dir data --prefix stock --output laya-stock-ft --bf16"
