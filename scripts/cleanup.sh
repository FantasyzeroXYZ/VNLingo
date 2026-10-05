#!/usr/bin/env bash
# cleanup.sh — 测试/构建垃圾选择性清理（分项开关，默认只显示帮助）
#
# 用法：
#   scripts/cleanup.sh --build           # 仓库构建产物（app/engine build + .gradle）
#   scripts/cleanup.sh --avd-snapshots   # 所有 AVD 的快照与 cache 分区（不影响游戏数据）
#   scripts/cleanup.sh --temp            # 仓库与 dev-cache 临时目录
#   scripts/cleanup.sh --all             # 以上全部
#   scripts/cleanup.sh --report          # 只看体积报告（等价 check-garbage.sh）
#
# 安全边界：AVD userdata（已装游戏/存档）与各项目源码/git 永不触碰。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AVD_HOME="${ANDROID_AVD_HOME:-D:/dev-cache/avd}"
ADB="${ADB:-D:/dev-cache/Android/Sdk/platform-tools/adb.exe}"

DO_BUILD=0; DO_SNAP=0; DO_TEMP=0
[[ "${1:-}" == "--report" ]] && { bash "$(dirname "$0")/check-garbage.sh"; exit 0; }
for arg in "$@"; do
    case "$arg" in
        --build)        DO_BUILD=1 ;;
        --avd-snapshots) DO_SNAP=1 ;;
        --temp)         DO_TEMP=1 ;;
        --all)          DO_BUILD=1; DO_SNAP=1; DO_TEMP=1 ;;
        *) echo "未知参数: $arg（见 --help 用法）"; exit 1 ;;
    esac
done
if [[ $DO_BUILD -eq 0 && $DO_SNAP -eq 0 && $DO_TEMP -eq 0 ]]; then
    echo "用法：scripts/cleanup.sh --build | --avd-snapshots | --temp | --all | --report"
    exit 1
fi

FREE_BEFORE=$(df /d 2>/dev/null | awk 'NR==2 { print $4 }')

if [[ $DO_BUILD -eq 1 ]]; then
    echo "[clean] 仓库构建产物..."
    rm -rf "$REPO_ROOT/app/build" "$REPO_ROOT/engine/build" \
           "$REPO_ROOT/.gradle" "$REPO_ROOT/build"
fi

if [[ $DO_SNAP -eq 1 ]]; then
    if "$ADB" devices 2>/dev/null | grep -q "emulator-"; then
        echo "[clean] 跳过 AVD 快照清理：检测到模拟器正在运行（先关闭再清理）"
        DO_SNAP=0
    else
    echo "[clean] AVD 快照与 cache 分区..."
    for avd in "$AVD_HOME"/*.avd; do
        [[ -d "$avd" ]] || continue
        rm -rf "$avd/snapshots" "$avd/cache.img.qcow2"
        echo "  cleaned: $(basename "$avd")"
    done
    fi
fi

if [[ $DO_TEMP -eq 1 ]]; then
    echo "[clean] 临时目录..."
    rm -rf "$REPO_ROOT/.tmp-test" "$REPO_ROOT/tmp" "/d/dev-cache/tmp"
fi

FREE_AFTER=$(df /d 2>/dev/null | awk 'NR==2 { print $4 }')
if [[ -n "$FREE_BEFORE" && -n "$FREE_AFTER" ]]; then
    echo "[clean] 释放约 $(( (FREE_AFTER - FREE_BEFORE) / 1024 )) MB（D 盘现剩余 $((FREE_AFTER / 1024)) MB）"
fi
