#!/usr/bin/env bash
# grant-permissions.sh — 一键授予 VNLingo 运行所需权限（免手动设置页操作）
#
# 用法：
#   scripts/grant-permissions.sh              # 自动检测设备并授权
#   SERIAL=emulator-5556 scripts/grant-permissions.sh   # 指定设备

set -euo pipefail

PACKAGE="com.tyranor.next"
ADB="${ADB:-D:/dev-cache/Android/Sdk/platform-tools/adb.exe}"

if [[ -z "${SERIAL:-}" ]]; then
    SERIAL="$("$ADB" devices | awk '$2 == "device" { print $1; exit }')"
    [[ -z "$SERIAL" ]] && { echo "[grant] 未检测到可用设备" >&2; exit 1; }
fi
echo "[grant] 使用设备: $SERIAL"

# 逐项尽力而为：OS 级别低于权限引入版本时（如 POST_NOTIFICATIONS 需 API 33+）
# pm grant 会报 Unknown permission，跳过即可（该 OS 上默认已授权）
grant_or_skip() {
    "$ADB" -s "$SERIAL" shell pm grant "$PACKAGE" "$1" 2>/dev/null         && echo "[grant] $1 ✓"         || echo "[grant] $1 跳过（本 OS 级别不存在或已默认授权）"
}
grant_or_skip android.permission.POST_NOTIFICATIONS

# 特殊权限（appops；注意命令是 cmd appops）
"$ADB" -s "$SERIAL" shell cmd appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow     && echo "[grant] MANAGE_EXTERNAL_STORAGE ✓"
"$ADB" -s "$SERIAL" shell cmd appops set "$PACKAGE" REQUEST_INSTALL_PACKAGES allow     && echo "[grant] REQUEST_INSTALL_PACKAGES ✓"

echo "[grant] 完成（POST_NOTIFICATIONS / MANAGE_EXTERNAL_STORAGE / REQUEST_INSTALL_PACKAGES）"
