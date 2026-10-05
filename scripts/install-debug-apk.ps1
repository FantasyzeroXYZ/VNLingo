# install-debug-apk.ps1 — 构建（可选）并安装 VNLingo debug APK 到模拟器/设备
#
# 用法（PowerShell）：
#   .\scripts\install-debug-apk.ps1                    # 交互式选择设备并安装
#   $env:BUILD=1; .\scripts\install-debug-apk.ps1      # 先构建再安装
#   $env:LAUNCH=1; .\scripts\install-debug-apk.ps1     # 安装后拉起主界面
#   $env:SERIAL="emulator-5556"; .\scripts\install-debug-apk.ps1   # 跳过选择，指定设备
#
# 注意：必须 --abi arm64-v8a 安装。模拟器（x86_64 镜像）经 ARM 转译运行引擎
# so；不带 --abi 时 package 管理器可能选错 ABI，启动即 UnsatisfiedLinkError
# （EM_AARCH64 vs EM_X86_64，见 MEMORY）。
#
# 移植自 Tyranor-Next/scripts/install-debug-apk.ps1（同源项目）。

$ErrorActionPreference = 'Stop'

# 让脚本从自身所在目录推导仓库根目录（scripts 的上一级）
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = (Resolve-Path (Join-Path $ScriptDir '..')).Path

$Apk     = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$Package = 'com.tyranor.next'
$Adb     = if ($env:ADB)       { $env:ADB }       else { 'D:/dev-cache/Android/Sdk/platform-tools/adb.exe' }
$Jdk     = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:/dev-cache/jdk-17.0.20.1+1' }

$DoBuild  = ($env:BUILD  -eq '1')
$DoLaunch = ($env:LAUNCH -eq '1')

# ---------------------------------------------------------------
# 设备检测与选择
# ---------------------------------------------------------------
function Get-AdbDevices {
    # 返回 @( @{ Serial=...; State=... } ) 形式，只包含 state 为 device / unauthorized / offline 的行
    $raw = & $Adb devices 2>$null
    $list = @()
    foreach ($line in $raw) {
        if ($line -match '^\s*(\S+)\s+(device|unauthorized|offline|bootloader)\s*$') {
            $list += [pscustomobject]@{
                Serial = $Matches[1]
                State  = $Matches[2]
            }
        }
    }
    return $list
}

$Serial = $env:SERIAL
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = Get-AdbDevices

    if ($devices.Count -eq 0) {
        Write-Error "[install] 未检测到任何设备。请启动模拟器或连接真机后重试。"
        exit 1
    }

    $available = @($devices | Where-Object { $_.State -eq 'device' })

    if ($available.Count -eq 0) {
        Write-Host "[install] 检测到以下设备，但均不可用（未授权/离线）："
        $devices | ForEach-Object { Write-Host ("  - {0}  [{1}]" -f $_.Serial, $_.State) }
        Write-Error "[install] 请先在设备上授权 USB 调试，或检查模拟器状态。"
        exit 1
    }

    if ($available.Count -eq 1) {
        $Serial = $available[0].Serial
        Write-Host "[install] 自动选择唯一可用设备: $Serial"
    }
    else {
        Write-Host "[install] 检测到多个可用设备，请选择："
        for ($i = 0; $i -lt $available.Count; $i++) {
            Write-Host ("  [{0}] {1}" -f ($i + 1), $available[$i].Serial)
        }
        while ($true) {
            $choice = Read-Host "请输入序号 (1-$($available.Count))"
            $idx = 0
            if ([int]::TryParse($choice, [ref]$idx) -and $idx -ge 1 -and $idx -le $available.Count) {
                $Serial = $available[$idx - 1].Serial
                break
            }
            Write-Host "输入无效，请重新输入。"
        }
        Write-Host "[install] 使用设备: $Serial"
    }
}
else {
    Write-Host "[install] 使用环境变量指定的设备: $Serial"
}

# ---------------------------------------------------------------
# 构建（可选）
# ---------------------------------------------------------------
if ($DoBuild -or -not (Test-Path $Apk)) {
    Write-Host '[install] building debug APK...'
    Push-Location $RepoRoot
    try {
        $env:JAVA_HOME = $Jdk
        & .\gradlew.bat :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "gradlew failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}

if (-not (Test-Path $Apk)) {
    Write-Error "[install] APK not found: $Apk"
    exit 1
}

# ---------------------------------------------------------------
# 安装
# ---------------------------------------------------------------
& $Adb -s $Serial install -r --abi arm64-v8a $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE" }
Write-Host "[install] installed to ${Serial}: $Package"

# ---------------------------------------------------------------
# 启动（可选）
# ---------------------------------------------------------------
if ($DoLaunch) {
    & $Adb -s $Serial shell am force-stop $Package 2>$null
    & $Adb -s $Serial shell am start -n "$Package/.MainActivity"
}
