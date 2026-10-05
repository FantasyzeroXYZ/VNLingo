#!/usr/bin/env bash
# install-debug-apk.sh — 构建（可选）并安装 VNLingo debug APK 到模拟器/设备
#
# 用法（Git Bash）：
#   scripts/install-debug-apk.sh              # 安装已构建的 APK
#   BUILD=1 scripts/install-debug-apk.sh      # 先构建再安装
#   LAUNCH=1 scripts/install-debug-apk.sh     # 安装后拉起主界面
#   SERIAL=emulator-5556 scripts/install-debug-apk.sh   # 指定设备
#
# 注意：必须 --abi arm64-v8a 安装。模拟器（x86_64 镜像）经 ARM 转译运行引擎
# so；不带 --abi 时 package 管理器可能选错 ABI，启动即 UnsatisfiedLinkError
# （EM_AARCH64 vs EM_X86_64，见 MEMORY）。
#
# 移植自 Tyranor-Next/scripts/install-debug-apk.sh（同源项目）。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.tyranor.next"
ADB="${ADB:-D:/dev-cache/Android/Sdk/platform-tools/adb.exe}"
JDK="${JAVA_HOME:-D:/dev-cache/jdk-17.0.20.1+1}"

# 自动检测：取第一台 state=device 的设备；SERIAL 环境变量可覆盖
if [[ -z "${SERIAL:-}" ]]; then
    SERIAL="$("$ADB" devices | awk '$2 == "device" { print $1; exit }')"
    if [[ -z "$SERIAL" ]]; then
        echo "[install] 未检测到可用设备（adb devices 无 state=device 行）" >&2
        exit 1
    fi
    echo "[install] 自动检测设备: $SERIAL"
fi

if [[ "${BUILD:-0}" == "1" || ! -f "$APK" ]]; then
    echo "[install] building debug APK..."
    (cd "$REPO_ROOT" && JAVA_HOME="$JDK" ./gradlew :app:assembleDebug)
fi

if [[ ! -f "$APK" ]]; then
    echo "[install] APK not found: $APK" >&2
    exit 1
fi

"$ADB" -s "$SERIAL" install -r --abi arm64-v8a "$APK"
echo "[install] installed to $SERIAL: $PACKAGE"

if [[ "${LAUNCH:-0}" == "1" ]]; then
    "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE" || true
    "$ADB" -s "$SERIAL" shell am start -n "$PACKAGE/.MainActivity"
fi
