# 编译并运行 NowWeather 性能基准（只测 core，不需要 Minecraft / 网络）
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jdk = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += @('D:\jdk-18.0.2', 'D:\jdk-17', 'D:\Java22', 'D:\J21',
    'C:\Program Files\Eclipse Adoptium\jdk-17*', 'C:\Program Files\Java\jdk-17*')
foreach ($c in $candidates) {
    $found = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $found) { continue }
    $javacExe = Join-Path $found.FullName 'bin\javac.exe'
    if (-not (Test-Path $javacExe)) { continue }
    # ★ 必须校验版本：--release 是 JDK 9+ 才有的选项。
    #   以前这里直接用 $env:JAVA_HOME，环境变量指向旧 JDK 时会以
    #   「javac: 无效的标记: --release」失败，而且看不出是 JDK 选错了。
    $release = Join-Path $found.FullName 'release'
    $major = 0
    if (Test-Path $release) {
        $line = Select-String -Path $release -Pattern '^JAVA_VERSION="?(\d+)' | Select-Object -First 1
        if ($line) { $major = [int]$line.Matches[0].Groups[1].Value }
    }
    if ($major -ge 17) { $jdk = $found.FullName; break }
    Write-Host "跳过 $($found.FullName)（Java $major，需要 17+）" -ForegroundColor DarkGray
}
if (-not $jdk) {
    throw "找不到 JDK 17+。请把 JAVA_HOME 指到 JDK 17/18/21/22，或用 -Jdk 参数指定。"
}
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
Write-Host "使用 JDK: $jdk" -ForegroundColor Cyan

$out = Join-Path $root 'tools\out\bench'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @()
$sources += Get-ChildItem -Path 'core\src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Path 'core\src\test\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += (Resolve-Path 'tools\bench\Bench.java').Path

Write-Host "编译 $($sources.Count) 个源文件…" -ForegroundColor Cyan
$env:CLASSPATH = ''
& $javac -encoding UTF-8 --release 17 -nowarn -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "编译失败（退出码 $LASTEXITCODE）" }

$resRoot = Join-Path $root 'core\src\main\resources'
if (Test-Path $resRoot) { Copy-Item -Path (Join-Path $resRoot '*') -Destination $out -Recurse -Force }

$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $out com.nowweather.core.test.Bench
$code = $LASTEXITCODE
$ErrorActionPreference = $prev

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
exit $code
