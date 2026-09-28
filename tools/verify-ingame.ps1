# 一次跑完：重建 → 部署 → 重启两个真机服务端 → 全量回归
#
# 用法（在仓库根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-ingame.ps1
#
# 前置条件：两个真机 Forge 服务端已按 docs/IN_GAME_TEST_REPORT.md 装好，
# 且 server.properties 已开 RCON（1.18.2 → 25575 / 1.18.1 → 25576，密码 nowweather-test）。
# 本脚本会重启这两个服务端，并跑 /nowweather test all 全量矩阵。
#
# 三条硬规矩（都是踩过坑才加的）：
#   1) 绝不静默降级：任何一步失败直接抛错终止。以前 $ErrorActionPreference='Continue'
#      配上没有 -ErrorAction Stop 的 Copy-Item，拷贝失败会一路继续，最后测的是服务端里的
#      旧 jar，却打印「完成」——这是最坏的一类失败：看起来通过了，测的是旧版本。
#   2) 版本号绝不写死：优先读 gradle.properties 的 mod_version，读不到就扫描 build/libs；
#      两边都找不到直接抛错，而不是硬编码一个早就过期的文件名（曾写死 0.1.0-alpha.1）。
#   3) 停服务端绝不按进程名通杀：只终止「确认属于本测试服务端」的 java 进程，找不到就跳过。
$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $PSScriptRoot
$s1182 = 'D:\NowWeatherTestServer'
$s1181 = 'D:\NowWeatherTestServer1181'
$jbr = 'D:\IntelliJ IDEA 2023.3.3\jbr'
$rconPort1182 = 25575
$rconPort1181 = 25576
$testDirs = @($s1182, $s1181)
$rconPorts = @($rconPort1182, $rconPort1181)
$pidRecordName = '.nowweather-test-server.pid'

# ---- 工具函数 ----

# 列出机器上所有 java 进程（只读，不做任何终止动作）。
function Get-JavaProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction SilentlyContinue)
}

# 收集「确认属于本测试服务端」的 java 进程 PID，来源三选一并集：
#   1) 本脚本上次启动时写下的 .nowweather-test-server.pid（PID + 命令行双重校验，防 PID 复用）
#   2) 命令行里带本服务端目录 / mods 目录 / run-server 路径的 java 进程
#   3) 正在监听本服务端 RCON 端口的进程
# 三条都落空就返回空集合 —— 宁可跳过强杀，也绝不退化成「杀掉所有 java」。
function Get-TestServerPids {
    param([string[]]$Dirs, [int[]]$Ports)
    $found = @{}
    $all = Get-JavaProcesses
    $byId = @{}
    foreach ($p in $all) { $byId[[int]$p.ProcessId] = $p }

    # 来源 1：上次运行时记录的 PID
    foreach ($dir in $Dirs) {
        $recordPath = Join-Path $dir $pidRecordName
        if (-not (Test-Path $recordPath)) { continue }
        $parts = ([System.IO.File]::ReadAllText($recordPath)) -split "`t", 2
        $recordedPid = 0
        if (-not [int]::TryParse($parts[0].Trim(), [ref]$recordedPid)) { continue }
        $proc = $byId[$recordedPid]
        if (-not $proc) { continue }
        # 双重校验：命令行必须与记录一致，否则说明这个 PID 已被别的进程复用，动它不安全
        if ($parts.Count -ge 2 -and ([string]$proc.CommandLine) -eq $parts[1]) {
            $found[$recordedPid] = $true
        }
    }

    # 来源 2：命令行里带本测试服务端路径
    $markers = @('run-server')
    foreach ($dir in $Dirs) { $markers += $dir; $markers += (Join-Path $dir 'mods') }
    foreach ($p in $all) {
        $cmd = [string]$p.CommandLine
        if ([string]::IsNullOrWhiteSpace($cmd)) { continue }
        foreach ($m in $markers) {
            if ($cmd.IndexOf($m, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $found[[int]$p.ProcessId] = $true
                break
            }
        }
    }

    # 来源 3：RCON 端口监听者（真正在跑的是哪个进程，端口最诚实）
    foreach ($port in $Ports) {
        try {
            $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if ($conn -and $byId.ContainsKey([int]$conn.OwningProcess)) {
                $found[[int]$conn.OwningProcess] = $true
            }
        } catch {
            # 拿不到端口信息就少一条线索，不影响其它两条
        }
    }

    return @($found.Keys)
}

# 记录本脚本启动的服务端 java 进程（PID + 命令行），下次运行据此精确停止。
# Start-Process 起的是 cmd.exe（run.bat），真正的 java.exe 是它的直接子进程；
# run.bat 里是直接调用 java（不是 start），所以父子关系可靠。认不出来就返回 $null，只跳过。
function Save-ServerProcessRecord {
    param([string]$Dir, [int]$LauncherPid)
    $child = $null
    for ($i = 0; $i -lt 30; $i++) {
        $child = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $LauncherPid" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe' }) | Select-Object -First 1
        if ($child) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $child) { return $null }
    $line = "$($child.ProcessId)`t$($child.CommandLine)"
    [System.IO.File]::WriteAllText((Join-Path $Dir $pidRecordName), $line,
        (New-Object System.Text.UTF8Encoding($false)))
    return [int]$child.ProcessId
}

# 定位构建产物：优先 gradle.properties 的 mod_version，其次扫描 build/libs 取最新的那个。
# 都找不到就抛错 —— 绝不拿服务端里的旧 jar 顶替。
function Resolve-ModJar {
    param([string]$Mc)
    $libs = Join-Path $proj "versions\forge-$Mc\build\libs"
    $ver = $null
    $props = Join-Path $proj 'gradle.properties'
    if (Test-Path $props) {
        $m = Select-String -Path $props -Pattern '^\s*mod_version\s*=\s*(\S+)' | Select-Object -First 1
        if ($m) { $ver = $m.Matches[0].Groups[1].Value.Trim() }
    }
    if ($ver) {
        $exact = Join-Path $libs "NowWeather-$Mc-$ver.jar"
        if (Test-Path $exact) { return (Get-Item $exact) }
    }
    $cands = @(Get-ChildItem -Path $libs -Filter "NowWeather-$Mc-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object LastWriteTime -Descending)
    if ($cands.Count -eq 0) {
        throw "找不到 $Mc 的构建产物：$libs 下既没有 mod_version=$ver 对应的 jar，也没有任何 NowWeather-$Mc-*.jar。请先构建成功 —— 本脚本绝不拿服务端里的旧 jar 顶替。"
    }
    return $cands[0]
}

Write-Output '=== 1) 重建两个 jar ==='
$env:JAVA_HOME = 'D:\jdk-18.0.2'
$env:CLASSPATH = ''
# 用 Start-Process 落盘再读，避免 PS 5.1 里 `native 2>&1` 在 ErrorActionPreference='Stop'
# 下把 stderr 当成错误记录直接抛出；退出码照样显式检查，不放过任何失败。
$logDir = Join-Path $proj 'tools\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$buildOut = Join-Path $logDir 'verify-build.out.log'
$buildErr = Join-Path $logDir 'verify-build.err.log'
$build = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList '/c', 'gradlew.bat', ':forge-1.18.2:build', ':forge-1.18.1:build', '--no-daemon', '--console=plain' `
    -WorkingDirectory $proj -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr
Get-Content $buildOut, $buildErr -ErrorAction SilentlyContinue |
    Select-String -Pattern 'BUILD|error:' | Select-Object -Last 6 | ForEach-Object { $_.Line.Trim() }
if ($build.ExitCode -ne 0) {
    throw "构建失败（gradlew 退出码 $($build.ExitCode)），已终止：绝不部署旧 jar 去假装通过。完整日志见 $buildOut / $buildErr"
}

Write-Output '=== 2) 停掉旧服务端 ==='
foreach ($port in $rconPorts) {
    try { & "$proj\tools\rcon.ps1" -Command 'stop' -Port $port -TimeoutSec 15 | Out-Null } catch { }
}
for ($i = 0; $i -lt 40; $i++) {
    if (@(Get-TestServerPids -Dirs $testDirs -Ports $rconPorts).Count -eq 0) { break }
    Start-Sleep -Seconds 2
}
# 注意：这里以前写的是 `Get-Process java | Stop-Process -Force`，会把机器上所有 java
# 统统干掉 —— Gradle daemon、IntelliJ IDEA、以及任何别人的 java 程序全中招，属于事故级行为。
# 现在只终止「确认属于本测试服务端」的进程（PID 记录 / 命令行路径 / RCON 端口监听者三选一），
# 一条线索都没有就跳过强杀，绝不退化成按进程名通杀。
$stale = @(Get-TestServerPids -Dirs $testDirs -Ports $rconPorts)
if ($stale.Count -gt 0) {
    foreach ($procId in $stale) {
        Write-Output "   强制终止测试服务端 java 进程 PID $procId"
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Output '   没有探测到残留的测试服务端 java 进程（跳过强杀；RCON stop 应已把它停干净）'
}
Start-Sleep -Seconds 3
Write-Output '   两个服务端已停止'

Write-Output '=== 3) 部署新 jar ==='
$jar1182 = Resolve-ModJar -Mc '1.18.2'
$jar1181 = Resolve-ModJar -Mc '1.18.1'
Write-Output "   1.18.2 产物：$($jar1182.Name)（$($jar1182.Length) 字节，$($jar1182.LastWriteTime)）"
Write-Output "   1.18.1 产物：$($jar1181.Name)（$($jar1181.Length) 字节，$($jar1181.LastWriteTime)）"

foreach ($pair in @(@($jar1182, $s1182), @($jar1181, $s1181))) {
    $jar = $pair[0]
    $dest = $pair[1]
    $modsDir = Join-Path $dest 'mods'
    New-Item -ItemType Directory -Force -Path $modsDir | Out-Null

    # 清掉 mods 里其它版本的 NowWeather jar：同时留两个会让 Forge 加载到旧版本，
    # 这正是「测的是旧 jar 却报告成功」的另一条路径（服务端目录里就曾留着 26u39r）。
    foreach ($staleJar in @(Get-ChildItem -Path $modsDir -Filter 'NowWeather-*.jar' -File -ErrorAction SilentlyContinue)) {
        if ($staleJar.Name -ne $jar.Name) {
            Remove-Item $staleJar.FullName -Force -ErrorAction Stop
            Write-Output "   移除旧版本 jar：$($staleJar.Name)"
        }
    }

    Copy-Item $jar.FullName $modsDir -Force -ErrorAction Stop
    $deployed = Get-Item (Join-Path $modsDir $jar.Name) -ErrorAction Stop
    if ($deployed.Length -ne $jar.Length) {
        throw "部署校验失败：$($deployed.FullName) 大小 $($deployed.Length) 与构建产物 $($jar.Length) 不一致。"
    }
    Write-Output "   已部署 $($deployed.Name) → $modsDir（$($deployed.Length) 字节，$($deployed.LastWriteTime)）"
}

Write-Output '=== 4) 启动两个服务端（独立进程）==='
$env:PATH = "$jbr\bin;" + $env:PATH
$env:JAVA_HOME = $jbr
Remove-Item "$s1182\server.log","$s1181\server.log" -ErrorAction SilentlyContinue
$launcher1182 = Start-Process -FilePath "$s1182\run.bat" -ArgumentList '--nogui' -WorkingDirectory $s1182 `
    -RedirectStandardOutput "$s1182\server.log" -RedirectStandardError "$s1182\server.err.log" -WindowStyle Hidden -PassThru
$java1182 = Save-ServerProcessRecord -Dir $s1182 -LauncherPid $launcher1182.Id
if ($java1182) { Write-Output "   1.18.2 服务端 java PID = $java1182" } else { Write-Output '   1.18.2 服务端 java PID 未捕获（不影响流程，只是下次停止时少一条线索）' }
Start-Sleep -Seconds 10
$launcher1181 = Start-Process -FilePath "$s1181\run.bat" -ArgumentList '--nogui' -WorkingDirectory $s1181 `
    -RedirectStandardOutput "$s1181\server.log" -RedirectStandardError "$s1181\server.err.log" -WindowStyle Hidden -PassThru
$java1181 = Save-ServerProcessRecord -Dir $s1181 -LauncherPid $launcher1181.Id
if ($java1181) { Write-Output "   1.18.1 服务端 java PID = $java1181" } else { Write-Output '   1.18.1 服务端 java PID 未捕获（不影响流程，只是下次停止时少一条线索）' }

Write-Output '=== 5) 等待就绪 ==='
$ok75 = $false; $ok76 = $false
for ($i = 0; $i -lt 90; $i++) {
    if (-not $ok75) { try { $o = & "$proj\tools\rcon.ps1" -Command 'list' -Port 25575 -TimeoutSec 8; if ($o -match 'players online') { $ok75 = $true; Write-Output "   1.18.2 就绪" } } catch { } }
    if (-not $ok76) { try { $o = & "$proj\tools\rcon.ps1" -Command 'list' -Port 25576 -TimeoutSec 8; if ($o -match 'players online') { $ok76 = $true; Write-Output "   1.18.1 就绪" } } catch { } }
    if ($ok75 -and $ok76) { break }
    Start-Sleep -Seconds 5
}
if (-not ($ok75 -and $ok76)) { Write-Output "★ 就绪失败 1.18.2=$ok75 1.18.1=$ok76"; exit 1 }

Start-Sleep -Seconds 15
Write-Output '=== 6) 全量回归 ==='
Write-Output '--- 1.18.2 ---'
& "$proj\tools\rcon.ps1" -Command 'nowweather test all' -Port 25575 -TimeoutSec 300
Write-Output '--- 1.18.1 ---'
& "$proj\tools\rcon.ps1" -Command 'nowweather test all' -Port 25576 -TimeoutSec 300

Write-Output '=== 7) 时间同步 + 离线推算 ==='
foreach ($p in 25575, 25576) {
    Write-Output "--- 端口 $p ---"
    & "$proj\tools\rcon.ps1" -Command 'nowweather time sync' -Port $p -TimeoutSec 40
    & "$proj\tools\rcon.ps1" -Command 'nowweather offline' -Port $p -TimeoutSec 40
}
Write-Output '=== 完成 ==='
