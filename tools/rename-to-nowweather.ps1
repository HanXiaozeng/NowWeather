# 【一次性历史脚本，已停用】保留本文件仅为历史记录，请勿运行。
#
# 原本的用途：把项目从 WeatherSync 改名为 NowWeather（modId / 包名 / 类名 / 资源路径 一次性迁移）。
# 那次迁移早已完成。而脚本正文里含有 `-replace 'WeatherSync','NowWeather'` 这类
# 全局无条件替换，谁重跑一次都会不可逆地破坏源码（历史上 tools/gen-datasets.ps1
# 就是被一次过宽的批量替换改坏的）。因此下面加了一道硬护栏：一旦检测到迁移已完成，
# 直接 throw 终止，绝不允许误伤。
# 正文实现原样保留在文件末尾，仅供查阅。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# ---- 硬护栏：迁移已完成 → 直接终止 ----
# 判据（任一成立即视为已完成）：
#   1) 新包名下已有 NowWeatherCore.java
#   2) 旧包目录 com/weathersync 已不存在
#   3) 根目录构建文件里已经完全没有旧名
# 库里三个判据全部成立，所以本脚本在当前仓库上永远跑不到正文。
$newMarker = Join-Path $root 'core\src\main\java\com\nowweather\core\NowWeatherCore.java'
$oldPackageDir = Join-Path $root 'core\src\main\java\com\weathersync'
$migrationDone = (Test-Path $newMarker) -or (-not (Test-Path $oldPackageDir))
if (-not $migrationDone) {
    $rootHits = @(Select-String -Path (Join-Path $root 'gradle.properties'),
            (Join-Path $root 'settings.gradle'), (Join-Path $root 'build.gradle') `
            -Pattern 'weathersync' -SimpleMatch -ErrorAction SilentlyContinue)
    $migrationDone = ($rootHits.Count -eq 0)
}
if ($migrationDone) {
    throw '迁移已完成，本脚本仅供历史参考；重跑会破坏源码（正文里的 -replace 是无条件全局替换）。已终止。'
}

Set-Location $root

$enc = New-Object System.Text.UTF8Encoding($false)

function Rewrite-File {
    param([string]$Path, [bool]$NameSpaceOnly = $false)
    $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    $orig = $text
    if ($NameSpaceOnly) {
        $text = $text -replace 'com\.weathersync', 'com.nowweather'
    } else {
        $text = $text -replace 'com\.weathersync', 'com.nowweather'
        $text = $text -replace 'WeatherSync', 'NowWeather'
        $text = $text -replace 'weathersync', 'nowweather'
    }
    if ($text -ne $orig) {
        [System.IO.File]::WriteAllText($Path, $text, $enc)
        return $true
    }
    return $false
}

# ---- 1) 源码内容替换（Java 全量；文档只改命名空间引用）----
$javaFiles = Get-ChildItem -Path 'core', 'versions', 'tools' -Recurse -Filter *.java -ErrorAction SilentlyContinue
$changed = 0
foreach ($f in $javaFiles) { if (Rewrite-File -Path $f.FullName) { $changed++ } }
Write-Output "Java 文件已更新: $changed"

foreach ($f in @(Get-ChildItem -Path 'versions' -Recurse -Include 'mods.toml', 'pack.mcmeta' -ErrorAction SilentlyContinue)) {
    Rewrite-File -Path $f.FullName | Out-Null
}
foreach ($f in @('gradle.properties', 'settings.gradle', 'README.md', 'docs/API.md',
        'docs/ROADMAP.md', 'docs/BIOME_CLIMATE.md')) {
    if (Test-Path $f) { Rewrite-File -Path $f -NameSpaceOnly $false | Out-Null; Write-Output "updated: $f" }
}
foreach ($f in @(Get-ChildItem -Path 'versions' -Recurse -Filter 'build.gradle' -ErrorAction SilentlyContinue)) {
    Rewrite-File -Path $f.FullName | Out-Null
}

# ---- 2) 目录迁移：包名 ----
$packageMoves = @(
    @{ From = 'core\src\main\java\com\weathersync'; To = 'core\src\main\java\com\nowweather' },
    @{ From = 'core\src\test\java\com\weathersync'; To = 'core\src\test\java\com\nowweather' },
    @{ From = 'versions\forge-common\src\main\java\com\weathersync'; To = 'versions\forge-common\src\main\java\com\nowweather' }
)
foreach ($m in $packageMoves) {
    if (Test-Path $m.From) {
        New-Item -ItemType Directory -Force -Path (Split-Path $m.To -Parent) | Out-Null
        if (Test-Path $m.To) { Remove-Item -Recurse -Force $m.To }
        Move-Item $m.From $m.To
        Write-Output "moved: $($m.From) -> $($m.To)"
    }
}

# ---- 3) 资源目录迁移：modId 命名空间 ----
$resourceMoves = @(
    @{ From = 'versions\forge-common\src\main\resources\data\weathersync'; To = 'versions\forge-common\src\main\resources\data\nowweather' },
    @{ From = 'versions\forge-common\src\main\resources\assets\weathersync'; To = 'versions\forge-common\src\main\resources\assets\nowweather' }
)
foreach ($m in $resourceMoves) {
    if (Test-Path $m.From) {
        Move-Item $m.From $m.To
        Write-Output "moved: $($m.From) -> $($m.To)"
    }
}

# ---- 4) 文件名迁移：WeatherSyncXxx.java -> NowWeatherXxx.java ----
$renamed = 0
foreach ($f in (Get-ChildItem -Path 'core', 'versions', 'tools' -Recurse -Filter '*WeatherSync*.java' -ErrorAction SilentlyContinue)) {
    $newName = $f.Name -replace 'WeatherSync', 'NowWeather'
    Rename-Item -Path $f.FullName -NewName $newName
    $renamed++
}
Write-Output "重命名文件: $renamed"

Write-Output ""
Write-Output "=== 残留检查（应为 0）==="
Write-Output ("java 中 weathersync 出现次数: " + (Get-ChildItem 'core', 'versions', 'tools' -Recurse -Filter *.java -ErrorAction SilentlyContinue | Select-String -Pattern 'weathersync' -SimpleMatch | Measure-Object).Count)
Write-Output ("java 中 WeatherSync 出现次数: " + (Get-ChildItem 'core', 'versions', 'tools' -Recurse -Filter *.java -ErrorAction SilentlyContinue | Select-String -Pattern 'WeatherSync' -SimpleMatch | Measure-Object).Count)
