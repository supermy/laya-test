#!/data/data/com.termux/files/usr/bin/bash
# NPU E2E:清理残留 runner(按端口)→ 跑决策 E2E
cd /data/data/com.termux/files/home/laya-test
# 杀掉占用 7878 的进程(通过 /proc/net/tcp 找 inode → pid)
INODE=$(awk '$2 ~ /:1ED6$/ && $4 == "0A" {print $10}' /proc/net/tcp | head -1)
if [ -n "$INODE" ]; then
  for p in /proc/[0-9]*; do
    if ls -l $p/fd 2>/dev/null | grep -q "socket:\[$INODE\]"; then
      pid=${p#/proc/}
      [ "$pid" != "$$" ] && kill -9 "$pid" 2>/dev/null && echo "killed stale runner $pid"
    fi
  done
  sleep 1
fi
export LAYA_RUNNER_LOCAL=1 LAYA_BACKEND=npu
export LITERT_DISP_DIR=$HOME/laya-test/npu-libs
export LD_LIBRARY_PATH=$HOME/laya-test/npu-libs
export LAYA_RUNNER_BIN=./litert-runner-npu2
export LAYA_MAIN=/sdcard/models/laya-litert-ticket/phone/laya_ml_s256_embeds_npu.tflite
export LAYA_ACT=/sdcard/models/laya-litert-ticket/phone/laya_ml_act_head_fp32.tflite
export LAYA_EMBED=/sdcard/models/laya-litert-ticket/token_embeddings_fp16.bin
export LAYA_SCORER=/sdcard/models/laya-litert-ticket/phone/laya_ml_scorer.bin
node e2e-litert-npu.mjs 3 --stop 2>&1 | grep -avE "KernelPreference|^VERBOSE|^INFO"
