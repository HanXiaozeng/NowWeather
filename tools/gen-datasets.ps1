<#
.SYNOPSIS
    重新生成生物群系气候数据集（JSON + 人类可读总表）。

.DESCRIPTION
    单一数据源 → 同时产出：
      versions/forge-common/src/main/resources/data/<modid>/climate/*.json
      docs/BIOME_CLIMATE_TABLE.md

    为什么用生成而不是手写：「可能出现的天气」白名单必须和运行时用的推导逻辑完全一致，
    生成器直接调用 WeatherAllowance.derive(...)，所以文档永远不会和代码脱节。

    输入：
      data/nowweather/climate/_vanilla_source_1.18.2.json   人工维护的原版 1.18 基线
      tools/gen 里的 laterVanillaBiomes()        1.19 ~ 26.x 新增群系
      docs/research/bop-biomes-1.18.2.md         超多生物群系（源码级数据）
      docs/research/terralith-tectonic.json      Terralith / Tectonic（源码级数据）

.EXAMPLE
    pwsh -File tools/gen-datasets.ps1
#>
param([string]$Jdk = "")

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

$coreOut = Join-Path $root 'tools\out\core'
$genOut = Join-Path $root 'tools\out\gen'
Remove-Item -Recurse -Force $coreOut, $genOut -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $coreOut, $genOut | Out-Null

$env:CLASSPATH = ''
$coreSources = Get-ChildItem -Path 'core\src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 --release 17 -d $coreOut $coreSources
if ($LASTEXITCODE -ne 0) { throw "核心编译失败" }

& $javac -encoding UTF-8 --release 17 -cp $coreOut -d $genOut 'tools\gen\GenerateClimateDatasets.java'
if ($LASTEXITCODE -ne 0) { throw "生成器编译失败" }

& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$coreOut;$genOut" com.nowweather.tools.GenerateClimateDatasets
if ($LASTEXITCODE -ne 0) { throw "生成失败" }

Write-Host "数据集已更新。别忘了提交 JSON 与文档的改动。" -ForegroundColor Green
