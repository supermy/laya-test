#!/data/data/com.termux/files/usr/bin/bash
# 清理所有残留 runner(脚本 cmdline 不含关键字,避免 pkill 自匹配)
ps -ef 2>/dev/null | grep -E "litert-runner" | grep -v grep | awk '{print $2}' | while read pid; do
  kill -9 "$pid" 2>/dev/null && echo "killed $pid"
done
sleep 1
cd /data/data/com.termux/files/home/laya-test
export LAYA_RUNNER_LOCAL=1 LAYA_BACKEND=npu
export LITERT_DISP_DIR=$HOME/laya-test/npu-libs
export LD_LIBRARY_PATH=$HOME/laya-test/npu-libs
export LAYA_RUNNER_BIN=./litert-runner-npu2
export LAYA_MAIN=${LAYA_MAIN:-/sdcard/models/laya-litert-ticket/phone/laya_ml_s256_embeds_neg.tflite}
export LAYA_ACT=/sdcard/models/laya-litert-ticket/phone/laya_ml_act_head_fp32.tflite
export LAYA_EMBED=/sdcard/models/laya-litert-ticket/token_embeddings_fp16.bin
node e2e-litert-npu.mjs "${1:-3}" --stop 2>&1 | grep -avE "KernelPreference|^VERBOSE|^INFO"
