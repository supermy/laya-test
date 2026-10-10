#!/data/data/com.termux/files/usr/bin/bash
ps -ef 2>/dev/null | grep -E "litert-runner" | grep -v grep | awk '{print $2}' | while read pid; do kill -9 "$pid" 2>/dev/null; done
sleep 1
cd /data/data/com.termux/files/home/laya-test
export LAYA_BACKEND=npu LITERT_DISP_DIR=$HOME/laya-test/npu-libs LD_LIBRARY_PATH=$HOME/laya-test/npu-libs
./litert-runner-npu2 /sdcard/models/laya-litert-ticket/phone/laya_ml_s256_embeds_neg.tflite /sdcard/models/laya-litert-ticket/phone/laya_ml_act_head_fp32.tflite /sdcard/models/laya-litert-ticket/token_embeddings_fp16.bin tcp:7878 /data/local/tmp/gpucache 2>$TMPDIR/runner-dbg.log &
echo $! > $TMPDIR/runner.pid
sleep 15
node dbg-npu-raw.mjs 2>&1 | tail -4
kill -9 $(cat $TMPDIR/runner.pid) 2>/dev/null
grep -aE "old-mode" $TMPDIR/runner-dbg.log | tail -2
