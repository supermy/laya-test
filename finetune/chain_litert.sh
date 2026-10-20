#!/usr/bin/env bash
# ============================================================================
# LiteRT GPU/NPU 转换链(逐业务):aet_convert → symlink → fp16_quant → split_negfix → AOT
# 用法: bash chain_litert.sh cancer peritoneal sec [traffic...]
# 日志: /data/ai/logs-cancer/litert-<biz>.log;单业务成功输出 <biz>-LITERT-OK
# ============================================================================
set -uo pipefail
cd ~/litert-conv
for B in "$@"; do
  LOG=/data/ai/logs-cancer/litert-$B.log
  {
    N=laya_$B
    echo "=== [$B] aet_convert $(date +%H:%M) ==="
    ~/aet/bin/python aet_convert.py --ckpt /data/ai/laya-$B-ft --out /data/ai/litert-$B-conv --name $N || { echo "$B-CONVERT-FAIL"; continue; }
    ln -sfn /data/ai/litert-$B-conv litert-$B
    echo "=== [$B] fp16_quant ==="
    ~/aet/bin/python fp16_quant.py --task $B || { echo "$B-FP16-FAIL"; continue; }
    echo "=== [$B] split_negfix ==="
    ~/aet/bin/python split_negfix.py --ckpt /data/ai/laya-$B-ft --out /data/ai/litert-$B-negfix --name $N || { echo "$B-NEGFIX-FAIL"; continue; }
    echo "=== [$B] AOT(MT6993) ==="
    /data/ai/litert-venv/bin/python - <<PYEOF
import traceback
from ai_edge_litert.aot.aot_compile import aot_compile
import ai_edge_litert.aot.vendors
from ai_edge_litert.aot.vendors.mediatek.target import Target, SocModel
try:
    r = aot_compile('/data/ai/litert-$B-negfix/${N}_s256_embeds.tflite',
        output_dir='/data/ai/laya-aot/out-$B', target=Target(soc_model=SocModel.MT6993), keep_going=False)
    print('RESULT:', r)
except Exception:
    traceback.print_exc(); raise
PYEOF
    [ -d /data/ai/laya-aot/out-$B ] && ls /data/ai/laya-aot/out-$B | grep -q apply_plugin && echo "$B-LITERT-OK" || echo "$B-AOT-FAIL"
  } > "$LOG" 2>&1
  tail -2 "$LOG"
done
echo LITERT-CHAIN-DONE
