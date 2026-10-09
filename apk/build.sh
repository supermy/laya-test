#!/usr/bin/env bash
# laya-test APK 构建 v2 — 内置 LiteRT GPU 推理(kotlinc + LiteRT AAR + jniLibs)
# 用法: bash build.sh [appId 默认 com.selfhost.layatest]
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
APPID="${1:-com.selfhost.layatest}"
SDK="$HOME/build/mytflm/sdk/android-13/android.jar"
KS="$HOME/apk/k90.keystore"
AAR="$HOME/litert-conv/litert-2.2.0.aar"
AARAPI="$HOME/litert-conv/litert-api-2.2.0.aar"
GWJARS="$HOME/litert-conv/gwjars"
NANOJAR="$GWJARS/nanohttpd-2.3.1.jar"
KOTLINC=${KOTLINC:-kotlinc}
OUT="$HERE/out"
VER="$(date +%H%M)"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/aar" "$OUT/jni"
MANIFEST="$HERE/AndroidManifest.xml"
if [ "$APPID" != "com.selfhost.layatest" ]; then
  sed "s/package=\"com.selfhost.layatest\"/package=\"$APPID\"/" "$MANIFEST" > "$OUT/AndroidManifest.xml"
  MANIFEST="$OUT/AndroidManifest.xml"
fi

echo "== [0/6] 解包 LiteRT AAR =="
unzip -oq "$AAR" -d "$OUT/aar" classes.jar jni/arm64-v8a/libLiteRt.so jni/arm64-v8a/libLiteRtClGlAccelerator.so
mv "$OUT/aar/classes.jar" "$OUT/aar/classes-rt.jar"
unzip -oq "$AARAPI" -d "$OUT/aar" classes.jar jni/arm64-v8a/liblitert_jni.so
mv "$OUT/aar/classes.jar" "$OUT/aar/classes-api.jar"
STDLIB="$(dirname $(readlink -f $(which kotlinc)))/../lib/kotlin-stdlib.jar"
echo "   stdlib: $STDLIB"

echo "== [0.5/6] 编译 laya-jni.so(LiteRT C API,已验证路径) =="
JNI_INC="$HOME/litert-sdk/litert_cc_sdk"
JNI_LIB="$HOME/litert-sdk/aar/jni/arm64-v8a"
clang -c -fPIC -O2 -I"$JNI_INC" -DLITERT_DISABLE_OPENGL_SUPPORT -DLITERT_DISABLE_ION_SUPPORT -DLITERT_DISABLE_DMABUF_SUPPORT -DLITERT_DISABLE_AHWB_SUPPORT \
  "$HERE/src/laya-jni.c" -o "$OUT/laya-jni.o"
for cc in litert_gpu_options litert_compiler_options; do
  clang++ -c -fPIC -O2 -std=c++17 -I"$JNI_INC" -DLITERT_DISABLE_OPENGL_SUPPORT -DLITERT_DISABLE_ION_SUPPORT -DLITERT_DISABLE_DMABUF_SUPPORT -DLITERT_DISABLE_AHWB_SUPPORT \
    "$JNI_INC/litert/c/options/$cc.cc" -o "$OUT/$cc.o"
done
clang++ -c -fPIC -O2 -std=c++17 -I"$JNI_INC" -DLITERT_DISABLE_OPENGL_SUPPORT -DLITERT_DISABLE_ION_SUPPORT -DLITERT_DISABLE_DMABUF_SUPPORT -DLITERT_DISABLE_AHWB_SUPPORT \
  "$JNI_INC/litert/core/litert_toml_parser.cc" -o "$OUT/litert_toml_parser.o"
clang++ -c -fPIC -O2 -std=c++17 "$HERE/src/absl_stub.cc" -o "$OUT/absl_stub.o"
clang++ -shared "$OUT/laya-jni.o" "$OUT/absl_stub.o" "$OUT/litert_gpu_options.o" "$OUT/litert_compiler_options.o" "$OUT/litert_toml_parser.o" \
  -L"$JNI_LIB" -lLiteRt -llog -lm -o "$OUT/liblayajni.so"
echo "   liblayajni.so: $(du -h $OUT/liblayajni.so | cut -f1)"

echo "== [0.9/6] 资源 + R(Gateway.kt 等 Kotlin 引用 R,须先于 kotlinc) =="
mkdir -p "$OUT/gen"
aapt2 compile --dir "$HERE/res" -o "$OUT/res.zip"
aapt2 link -o "$OUT/res.apk" -I "$SDK" --manifest "$MANIFEST" --java "$OUT/gen" "$OUT/res.zip"
javac --release 11 -cp "$SDK" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

echo "== [1/6] kotlinc (com.laya host) =="
$KOTLINC -jvm-target 11 -cp "$SDK:$OUT/classes:$OUT/aar/classes-rt.jar:$OUT/aar/classes-api.jar:$GWJARS/android-mail-1.6.7.jar:$GWJARS/android-activation-1.6.7.jar:$GWJARS/paho-mqttv3-1.2.5.jar:$NANOJAR" \
  -d "$OUT/classes" $(find "$HERE/src" -name '*.kt')

echo "== [2/6] javac =="
javac --release 11 -cp "$SDK:$OUT/classes:$OUT/aar/classes-rt.jar:$OUT/aar/classes-api.jar:$GWJARS/android-mail-1.6.7.jar:$GWJARS/android-activation-1.6.7.jar:$GWJARS/paho-mqttv3-1.2.5.jar:$NANOJAR:$STDLIB" \
  -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

echo "== [3/6] d8 =="
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
d8 --min-api 31 --lib "$SDK" --lib "$OUT/aar/classes-rt.jar" --lib "$OUT/aar/classes-api.jar" --lib "$GWJARS/android-mail-1.6.7.jar" --lib "$GWJARS/android-activation-1.6.7.jar" --lib "$GWJARS/paho-mqttv3-1.2.5.jar" --lib "$NANOJAR" --lib "$STDLIB" \
  --output "$OUT/dex" "$OUT/classes.jar" "$OUT/aar/classes-rt.jar" "$OUT/aar/classes-api.jar" "$GWJARS/android-mail-1.6.7.jar" "$GWJARS/android-activation-1.6.7.jar" "$GWJARS/paho-mqttv3-1.2.5.jar" "$NANOJAR" "$STDLIB"

echo "== [4/6] aapt2 link =="
RES_ARG=""
[ -f "$OUT/res.zip" ] && RES_ARG="$OUT/res.zip"
aapt2 link -o "$OUT/base.apk" -I "$SDK" --manifest "$MANIFEST" \
  --min-sdk-version 31 --target-sdk-version 28 $RES_ARG

echo "== [5/6] pack dex + jniLibs =="
cd "$OUT" && zip -qj base.apk dex/classes.dex
mkdir -p lib/arm64-v8a
cp aar/jni/arm64-v8a/libLiteRt.so aar/jni/arm64-v8a/libLiteRtClGlAccelerator.so aar/jni/arm64-v8a/liblitert_jni.so lib/arm64-v8a/
cp "$OUT/liblayajni.so" lib/arm64-v8a/
# 独立进程推理 runner(17ms/问):二进制按 lib*.so 命名装入 nativeLibraryDir(exec 可执行)
cp "$HERE/jniExec/litert-runner" lib/arm64-v8a/librunner_rt.so
# NPU 独立进程 runner(MTK MDLA dispatch):同模式 lib*.so 命名;dispatch 库运行时经
# LITERT_DISP_DIR=nativeLibraryDir 加载,依赖仅 libLiteRt(AAR)+ libc++_shared,无 absl 污染
cp "$HERE/jniExec/litert-runner-npu" lib/arm64-v8a/libnpu_rt.so
cp "$HERE/jniExec/libLiteRtDispatch_MediaTek.so" lib/arm64-v8a/
for f in "$HERE"/jniExec/lib*; do cp "$f" lib/arm64-v8a/; done
cp $PREFIX/lib/libc++_shared.so lib/arm64-v8a/
# 只打包 AAR 自带库:AAR 静态链接 absl;外部 absl(如 C runner 构建)会造成符号版本污染,
# 导致 app 域 GPU tensor buffer 创建失败(tensor_buffer.h:128)
zip -qr base.apk lib
# jar 内非 class 资源(如 Paho 的 nls/*.properties)会被 d8 丢弃——按原路径加回,
# 否则 ResourceBundle.getBundle 抛 MissingResourceException 导致 MQTT 初始化失败
unzip -oq "$GWJARS/paho-mqttv3-1.2.5.jar" "org/eclipse/paho/client/mqttv3/internal/nls/*.properties" "META-INF/services/*" -d "$OUT/res-classes"
(cd "$OUT/res-classes" && zip -qr ../base.apk org META-INF/services)
cd "$HERE"
zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== [6/6] sign =="
apksigner sign --ks "$KS" --ks-pass pass:k90pass --ks-key-alias k90 \
  --out "$HERE/layaoffice-$VER.apk" "$OUT/aligned.apk"
apksigner verify --print-certs "$HERE/layaoffice-$VER.apk" | head -2
echo "完成: $HERE/layaoffice-$VER.apk (appId=$APPID)"
