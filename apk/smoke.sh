#!/usr/bin/env bash
# APK 冒烟测试(真机):安装(可选)→ 启动 → UI 断言 → 决策 API 验证
# 用法: bash smoke.sh <adb序列号> [--install <apk>]
#   序列号: 无线调试形如 172.100.25.184:34423(需已配对;手机亮屏解锁状态)
# 退出码: 0 全部通过;非 0 有 FAIL(输出 PASS/FAIL 明细)
set -uo pipefail
SERIAL="${1:-}"; shift || true
INSTALL_APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --install) INSTALL_APK="$2"; shift 2;;
    *) echo "未知参数: $1"; exit 1;;
  esac
done
[ -z "$SERIAL" ] && { echo "用法: bash smoke.sh <serial> [--install <apk>]"; exit 1; }
A="adb -s $SERIAL"
TD="$PWD/smoke-tmp"; rm -rf "$TD"; mkdir -p "$TD"; export TD

PASS=0; FAIL=0
chk() { local name="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "PASS: $name"; PASS=$((PASS+1))
  else echo "FAIL: $name"; FAIL=$((FAIL+1)); fi
}
dumpfile() { # dumpfile <名>:设备 dump → 本地文件,回显文件路径
  $A shell uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1
  $A shell cat /sdcard/smoke.xml > "$TD/$1" 2>/dev/null
  echo "$TD/$1"
}
btnxy() { # btnxy <文件> <按钮文本>
  python3 - "$1" "$2" <<'PYEOF'
import sys, re, os
xml = open(os.environ.get('TD', '.') + '/' + sys.argv[1], encoding='utf-8').read()
m = re.search(r'text="' + sys.argv[2] + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
print(f"{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}" if m else "")
PYEOF
}

echo "== [1/5] 连接与安装 =="
if [[ "$SERIAL" == *:* ]]; then adb connect "$SERIAL" >/dev/null 2>&1; fi
if [ -n "$INSTALL_APK" ]; then
  if $A install -r "$INSTALL_APK" 2>&1 | grep -q Success; then echo "已安装: $INSTALL_APK"
  else echo "安装失败"; exit 1; fi
fi
$A shell "input keyevent KEYCODE_WAKEUP" 2>/dev/null

echo "== [2/5] 启动与主页断言 =="
$A shell "am force-stop com.selfhost.layatest; am start -n com.selfhost.layatest/.MainActivity" >/dev/null 2>&1
sleep 6
HOME_XML=$(dumpfile smoke_home.xml)
chk "应用标题" grep -q "智能决策业务台\|Laya Decision Console" "$HOME_XML"
chk "tab 决策" grep -q 'text="决策"' "$HOME_XML"
chk "tab 报表" grep -q 'text="报表"' "$HOME_XML"
chk "tab 网关" grep -q 'text="网关"' "$HOME_XML"
chk "tab 系统" grep -q 'text="系统"' "$HOME_XML"
chk "标题栏语言按钮" grep -qE "🌐A|&#127760;|>中<|>EN<" "$HOME_XML"

echo "== [3/5] 网关页 + 决策 API 启动 =="
$A shell "input tap 796 2528; sleep 1.5" >/dev/null 2>&1
# 逐屏滚动找 API 按钮(最多 4 屏);"启动"则点,"停止"则已在运行
API_TOUCHED=0
for i in 1 2 3 4; do
  GW_XML=$(dumpfile smoke_gw.xml)
  XY=$(btnxy smoke_gw.xml "启动决策 API")
  if [ -n "$XY" ]; then
    $A shell "input tap $XY; sleep 2.5" >/dev/null 2>&1
    API_TOUCHED=1; break
  fi
  if grep -q "停止决策 API" "$GW_XML"; then API_TOUCHED=1; break; fi
  $A shell "input swipe 640 2000 640 800 400; sleep 1" >/dev/null 2>&1
done
GW_XML=$(dumpfile smoke_gw.xml)
if grep -q "启动决策 API\|停止决策 API" "$GW_XML"; then
  echo "PASS: 决策 API 按钮"; PASS=$((PASS+1))
elif curl -s --max-time 5 http://127.0.0.1:8790/health | grep -q '"ok":true'; then
  echo "PASS: 决策 API 按钮(等价:health 通,按钮滚出可视区)"; PASS=$((PASS+1))
else
  echo "FAIL: 决策 API 按钮"; FAIL=$((FAIL+1))
fi
[ "$API_TOUCHED" = "1" ] && echo "API 已触碰启动"

echo "== [4/5] 决策 API HTTP 验证 =="
$A shell "am start -n com.selfhost.layatest/.MainActivity" >/dev/null 2>&1; sleep 3 # 前台解冻(后台冻结会挂起请求)
chk "GET /health" bash -c "curl -s --max-time 8 http://127.0.0.1:8790/health | grep -q '\"ok\":true'"
chk "POST /decide(level)" bash -c "curl -s --max-time 30 -X POST http://127.0.0.1:8790/decide -H 'Content-Type: application/json' -d '{\"task\":\"ticket\",\"text\":\"smoke test: cannot login\"}' | grep -q '\"level\"'"

echo "== [5/5] 系统页断言 =="
$A shell "input tap 1110 2528; sleep 1.5; uiautomator dump /sdcard/smoke.xml" >/dev/null 2>&1
SYS_XML=$(dumpfile smoke_sys.xml)
chk "业务卡片" grep -q "已装入\|未装入\|Installed\|Not installed" "$SYS_XML"

echo "== 结果: PASS=$PASS FAIL=$FAIL =="
rm -rf "$TD"
[ "$FAIL" -eq 0 ]
