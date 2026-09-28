<#
.SYNOPSIS
    编译并运行核心自测（不需要 Minecraft、不需要网络）。

.DESCRIPTION
    core/ 与 Minecraft 完全解耦，所以可以脱离 Forge/Minecraft 单独验证：
    气候推断、预报合理性、UApiPro 解析、天气白名单、同步链路……
    共 350+ 项断言，正常几秒跑完。

.EXAMPLE
    pwsh -File tools/test-core.ps1
    pwsh -File tools/test-core.ps1 -Jdk "D:\jdk-17"
#>
param(
    [string]$Jdk = "",
    [switch]$KeepOutput
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Get-JdkMajorVersion {
    param([string]$JdkRoot)
    $release = Join-Path $JdkRoot 'release'
    if (Test-Path $release) {
        $line = Select-String -Path $release -Pattern '^JAVA_VERSION="?(\d+)' | Select-Object -First 1
        if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    }
    return 0
}

function Resolve-Jdk {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @('D:\jdk-17', 'D:\Java22', 'D:\J21', 'D:\jdk-18.0.2',
        'C:\Program Files\Eclipse Adoptium\jdk-17*', 'C:\Program Files\Java\jdk-17*',
        'D:\IntelliJ IDEA 2023.3.3\jbr')
    foreach ($candidate in $candidates) {
        $found = Get-Item $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $found) { continue }
        if (-not (Test-Path (Join-Path $found.FullName 'bin\javac.exe'))) { continue }
        # 必须是 JDK 17 及以上：源码级别与目标字节码都是 17
        if ((Get-JdkMajorVersion -JdkRoot $found.FullName) -ge 17) { return $found.FullName }
    }
    throw "找不到 JDK 17+。请用 -Jdk 指定路径，或把 JAVA_HOME 指向 JDK 17/21/22。"
}

$jdkHome = Resolve-Jdk -Explicit $Jdk
$javac = Join-Path $jdkHome 'bin\javac.exe'
$java = Join-Path $jdkHome 'bin\java.exe'
Write-Host "使用 JDK: $jdkHome" -ForegroundColor Cyan

$out = Join-Path $root 'tools\out\selftest'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @()
$sources += Get-ChildItem -Path 'core\src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Path 'core\src\test\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "编译 $($sources.Count) 个源文件…" -ForegroundColor Cyan

$env:CLASSPATH = ''
& $javac -encoding UTF-8 --release 17 -Xlint:all,-serial,-processing -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "编译失败（退出码 $LASTEXITCODE）" }

# 把 core 的资源（陆海掩码等）复制到输出目录，否则 -cp $out 加载不到
$resRoot = Join-Path $root 'core\src\main\resources'
if (Test-Path $resRoot) {
    Copy-Item -Path (Join-Path $resRoot '*') -Destination $out -Recurse -Force
    Write-Host "已复制 core 资源到输出目录" -ForegroundColor DarkGray
}

$mainClass = (Get-ChildItem -Path 'core\src\test\java' -Recurse -Filter 'CoreSelfTest.java').FullName
$pkgLine = Select-String -Path $mainClass -Pattern '^package\s+([\w\.]+);' | Select-Object -First 1
$main = "$($pkgLine.Matches[0].Groups[1].Value).CoreSelfTest"
Write-Host "运行 $main …" -ForegroundColor Cyan

# ★ 必须把 stderr 一起接进来（2>&1）。
# 原因：本脚本开头设了 $ErrorActionPreference='Stop'，而 PowerShell 会把原生程序的
# stderr 输出当成 ErrorRecord —— 于是「测试用例里合法地打了一行 WARN 日志」会让整个脚本
# 抛异常退出，连测试汇总都不打印，看起来就像自测失败。
# 这个坑是被 LLM 测试踩出来的：它故意验证「模型建议了非法配置键时会被拒绝」，
# 那条路径会打一行 WARN，结果整个测试跑不起来。
# ★ 关键：调用 java 前必须把 $ErrorActionPreference 放宽。
# PowerShell 会把原生程序的 stderr 输出转成 ErrorRecord，而本脚本开头设了 'Stop' ——
# 于是「某个测试用例合法地打了一行 WARN 日志」会让整个脚本抛异常退出，
# 连测试汇总都不打印，看起来就像自测失败。这个坑是被 LLM 测试踩出来的：
# 它故意验证「模型建议了非法配置键时会被拒绝」，那条路径会打一行 WARN。
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $out $main
$code = $LASTEXITCODE
$ErrorActionPreference = $previousPreference

if (-not $KeepOutput) { Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue }
if ($code -ne 0) { Write-Host "自测失败（退出码 $code）" -ForegroundColor Red; exit $code }
Write-Host "核心自测全部通过 ✔" -ForegroundColor Green
