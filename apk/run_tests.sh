#!/usr/bin/env bash
# levelOf 纯逻辑单测(Termux JVM 直跑,无需 Android 设备):
#   kotlinc 全包编译 + org.json 真实现(aligned mvn 镜像缓存)+ java 执行
#   用法: bash run_tests.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SDK="$HOME/build/mytflm/sdk/android-13/android.jar"
KLIB="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar"
OUT="${TMPDIR:-$PREFIX/tmp}/layatest-out-$$"
JSON_JAR="$HERE/test/org-json.jar"
AAR_RT="$HERE/test/litert-classes-rt.jar"
AAR_API="$HERE/test/litert-classes-api.jar"

# org.json 真实现(android.jar 里是 stub)
if [ ! -f "$JSON_JAR" ]; then
  echo "== 下载 org.json(aliyun mvn 镜像) =="
  mkdir -p "$HERE/test"
  curl -sL --max-time 60 "https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar" -o "$JSON_JAR"
fi

# LiteRT AAR 类(build.sh 同款)
if [ ! -f "$AAR_RT" ]; then
  echo "== 解包 LiteRT AAR =="
  unzip -oq "$HOME/litert-conv/litert-2.2.0.aar" classes.jar -d "$HERE/test/aar_rt" && mv "$HERE/test/aar_rt/classes.jar" "$AAR_RT"
  unzip -oq "$HOME/litert-conv/litert-api-2.2.0.aar" classes.jar -d "$HERE/test/aar_api" && mv "$HERE/test/aar_api/classes.jar" "$AAR_API"
fi

# 生成 R.java(Kotlin 引用 com.selfhost.layatest.R)
GEN="${TMPDIR:-$PREFIX/tmp}/layatest-gen-$$"
mkdir -p "$GEN"
aapt2 compile --dir "$HERE/res" -o "$GEN/res.zip"
aapt2 link -o "$GEN/res.apk" -I "$SDK" --manifest "$HERE/AndroidManifest.xml" --java "$GEN" "$GEN/res.zip"

echo "== kotlinc 编译(src 全包 + test) =="
rm -rf "$OUT"; mkdir -p "$OUT"
GWJARS="$HOME/litert-conv/gwjars"
kotlinc -jvm-target 11 -cp "$SDK:$JSON_JAR:$AAR_RT:$AAR_API:$GWJARS/android-mail-1.6.7.jar:$GWJARS/android-activation-1.6.7.jar:$GWJARS/paho-mqttv3-1.2.5.jar:$GWJARS/nanohttpd-2.3.1.jar" -d "$OUT" \
  $(find "$HERE/src" -name '*.kt') $(find "$GEN" -name '*.java') "$HERE/test/levelOf_test.kt"

echo "== JVM 运行 =="
java -cp "$OUT:$KLIB:$JSON_JAR:$AAR_RT:$AAR_API:$GWJARS/nanohttpd-2.3.1.jar:$GWJARS/paho-mqttv3-1.2.5.jar:$GWJARS/android-mail-1.6.7.jar:$GWJARS/android-activation-1.6.7.jar:$SDK" LevelOf_testKt
