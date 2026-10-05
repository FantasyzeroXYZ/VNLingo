#!/usr/bin/env bash
# check-garbage.sh — 测试/构建垃圾体积只读报告（不删除任何文件）
#
# 用法：
#   scripts/check-garbage.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AVD_HOME="${ANDROID_AVD_HOME:-D:/dev-cache/avd}"
line() { echo "--------------------------------------------------"; }

line
echo "[check] 仓库构建产物"
for d in "$REPO_ROOT/app/build" "$REPO_ROOT/engine/build" "$REPO_ROOT/.gradle" "$REPO_ROOT/build"; do
    if [[ -d "$d" ]]; then
        echo "  $(du -sh "$d" 2>/dev/null | cut -f1)  $d"
    fi
done

line
echo "[check] AVD（$AVD_HOME）"
if [[ -d "$AVD_HOME" ]]; then
    for avd in "$AVD_HOME"/*.avd; do
        [[ -d "$avd" ]] || continue
        echo "  $(du -sh "$avd" 2>/dev/null | cut -f1)  $(basename "$avd")"
        for part in snapshots cache.img.qcow2 userdata-qemu.img.qcow2; do
            if [[ -e "$avd/$part" ]]; then
                echo "      $(du -sh "$avd/$part" 2>/dev/null | cut -f1)  $part"
            fi
        done
    done
else
    echo "  AVD home 不存在: $AVD_HOME"
fi

line
echo "[check] 仓库临时目录"
for d in "$REPO_ROOT/.tmp-test" "$REPO_ROOT/tmp" "/d/dev-cache/tmp"; do
    if [[ -d "$d" ]]; then
        echo "  $(du -sh "$d" 2>/dev/null | cut -f1)  $d"
    fi
done

line
echo "[check] 磁盘剩余"
df -h /d 2>/dev/null | tail -1
line
echo "（只读报告；清理请用 scripts/cleanup.sh --help 查看分项）"
