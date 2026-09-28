# 把各改造小组产出的语言片段合并成 Minecraft 语言文件。
#
# 背景：i18n 改造由多个人并行进行，如果大家同时写 zh_cn.json / en_us.json
# 必然互相覆盖。所以约定「各自只往 tools/i18n/frag-<name>.json 追加键」，
# 由本脚本统一合并、去重、排序后写出两种语言。
#
# 片段格式（扁平对象）：
#   { "nowweather.weather.storm_rain": { "zh": "暴雨", "en": "Storm Rain" } }
#
# 用法：pwsh -File tools/merge-lang.ps1
#       pwsh -File tools/merge-lang.ps1 -Check    # 只校验不写出（CI/验收用）

[CmdletBinding()]
param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fragDir = Join-Path $root 'tools\i18n'
$langDir = Join-Path $root 'versions\forge-common\src\main\resources\assets\nowweather\lang'

if (-not (Test-Path $fragDir)) {
    Write-Host "找不到片段目录：$fragDir" -ForegroundColor Red
    exit 1
}

$merged = @{}   # key -> @{ zh = ...; en = ...; from = 片段文件名 }
$duplicates = @()
$empties = @()
$malformed = @()

foreach ($file in Get-ChildItem -Path $fragDir -Filter 'frag-*.json' -File | Sort-Object Name) {
    $raw = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    if ([string]::IsNullOrWhiteSpace($raw)) { continue }
    try {
        $obj = $raw | ConvertFrom-Json
    } catch {
        $malformed += "  $($file.Name) : JSON 解析失败 -> $($_.Exception.Message)"
        continue
    }
    foreach ($prop in $obj.PSObject.Properties) {
        $key = $prop.Name
        $val = $prop.Value
        $zh = [string]$val.zh
        $en = [string]$val.en

        if ([string]::IsNullOrWhiteSpace($key)) { continue }
        # 空值不是「无害的省略」：界面在 hint 为空时仍会回退去显示原始键名，
        # 所以空串等于没做 i18n。这里一律拦下。
        if ([string]::IsNullOrWhiteSpace($zh) -or [string]::IsNullOrWhiteSpace($en)) {
            $empties += "  $key  [$($file.Name)]  zh='$zh' en='$en'"
            continue
        }
        if ($merged.ContainsKey($key)) {
            $prev = $merged[$key]
            # 同键同值 = 无害重复；同键不同值 = 必须人工裁决
            if ($prev.zh -ne $zh -or $prev.en -ne $en) {
                $duplicates += "  $key`n      已定义于 [$($prev.from)] zh='$($prev.zh)'`n      又定义于 [$($file.Name)] zh='$zh'"
            }
            continue
        }
        $merged[$key] = @{ zh = $zh; en = $en; from = $file.Name }
    }
}

# 一次性把所有问题列全（而不是碰到第一条就退出）——修的时候一轮就能改完。
$problems = 0
if ($malformed.Count -gt 0) {
    $problems++
    Write-Host "有 $($malformed.Count) 份片段不是合法 JSON：" -ForegroundColor Red
    $malformed | ForEach-Object { Write-Host $_ }
}
if ($empties.Count -gt 0) {
    $problems++
    Write-Host "有 $($empties.Count) 个键的 zh 或 en 是空的（界面会因此显示原始键名，等于没翻译）：" -ForegroundColor Red
    $empties | ForEach-Object { Write-Host $_ }
}
if ($duplicates.Count -gt 0) {
    $problems++
    Write-Host "有 $($duplicates.Count) 个键被两个片段定义成了不同的文案，需要人工裁决：" -ForegroundColor Red
    $duplicates | ForEach-Object { Write-Host $_ }
}
if ($problems -gt 0) {
    Write-Host ""
    Write-Host "以上问题已全部列出（不是只报第一条）。修完重跑本脚本即可。" -ForegroundColor Yellow
    exit 1
}

if ($merged.Count -eq 0) {
    Write-Host "没有任何语言片段，什么都没做。" -ForegroundColor Yellow
    exit 0
}

# 稳定排序输出：键名升序，保证每次生成结果一致（便于 git diff 审阅）
$keys = $merged.Keys | Sort-Object

function Write-LangFile {
    param([string]$Lang, [string]$Path)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('{')
    $i = 0
    foreach ($k in $keys) {
        $i++
        $text = $merged[$k].$Lang
        # JSON 转义：反斜杠、双引号，以及控制字符
        $esc = $text.Replace('\', '\\').Replace('"', '\"').Replace("`r", '').Replace("`n", '\n').Replace("`t", '\t')
        $comma = if ($i -lt $keys.Count) { ',' } else { '' }
        [void]$sb.AppendLine("  `"$k`": `"$esc`"$comma")
    }
    [void]$sb.AppendLine('}')
    # 关键：不能写 BOM。Minecraft 的 Gson 解析器对 BOM 不友好。
    [System.IO.File]::WriteAllText($Path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
}

$zhPath = Join-Path $langDir 'zh_cn.json'
$enPath = Join-Path $langDir 'en_us.json'

if ($Check) {
    # 校验模式：比对现有语言文件与将要生成的内容是否一致
    $ok = $true
    foreach ($pair in @(@{ l = 'zh'; p = $zhPath }, @{ l = 'en'; p = $enPath })) {
        if (-not (Test-Path $pair.p)) { $ok = $false; continue }
        $current = ([System.IO.File]::ReadAllText($pair.p, [System.Text.Encoding]::UTF8) | ConvertFrom-Json)
        $currentKeys = $current.PSObject.Properties.Name | Sort-Object
        if (($currentKeys -join ',') -ne ($keys -join ',')) {
            Write-Host "$($pair.l) 与片段不一致（键集合不同）" -ForegroundColor Red
            $ok = $false
        }
    }
    if ($ok) { Write-Host "语言文件与片段一致，共 $($merged.Count) 个键。" -ForegroundColor Green; exit 0 }
    exit 1
}

New-Item -ItemType Directory -Force -Path $langDir | Out-Null
Write-LangFile -Lang 'zh' -Path $zhPath
Write-LangFile -Lang 'en' -Path $enPath

Write-Host "已合并 $($merged.Count) 个语言键（来自 $((Get-ChildItem -Path $fragDir -Filter 'frag-*.json' -File).Count) 个片段）：" -ForegroundColor Green
Write-Host "  $zhPath"
Write-Host "  $enPath"

# 顺手做一次「有没有人写了键但忘了用」的粗检：统计源码里 Text.tr("...") 的键，
# 与语言文件比对，把只出现在一边的键报出来 —— 这类漂移正是「过时内容」的温床。
$src = Join-Path $root 'core\src\main\java'
$src2 = Join-Path $root 'versions'
$usedKeys = @{}
foreach ($dir in @($src, $src2)) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Recurse -Filter '*.java' -File |
        Where-Object { $_.FullName -notmatch '\\build\\' } |
        ForEach-Object {
            $text = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
            foreach ($m in [regex]::Matches($text, 'Text\.tr\(\s*"([a-z0-9_.]+)"')) {
                $usedKeys[$m.Groups[1].Value] = $true
            }
            # 反射式键名（如 "nowweather.weather." + segment）无法静态提取，跳过
        }
}

# 以 "." 结尾的「键」其实是拼接前缀（Text.tr("nowweather.status." + x)），
# 静态扫描只能看到前半截，不是真的缺键 —— 单独归为一类，免得淹没真问题。
$prefixes = @($usedKeys.Keys | Where-Object { $_.EndsWith('.') } | Sort-Object)
$concrete = @($usedKeys.Keys | Where-Object { -not $_.EndsWith('.') })

$missing = @($concrete | Where-Object { -not $merged.ContainsKey($_) } | Sort-Object)
$unused = @($keys | Where-Object { -not $usedKeys.ContainsKey($_) } | Sort-Object)

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "以下 $($missing.Count) 个键被代码使用，但语言文件里没有（玩家会直接看到键名）：" -ForegroundColor Yellow
    $missing | Select-Object -First 60 | ForEach-Object { Write-Host "  $_" }
    if ($missing.Count -gt 60) { Write-Host "  …还有 $($missing.Count - 60) 个" }
}
if ($prefixes.Count -gt 0) {
    Write-Host ""
    Write-Host "以下 $($prefixes.Count) 个是拼接前缀（不是缺键，仅供你核对拼接出来的后半截是否都有定义）：" -ForegroundColor DarkGray
    $prefixes | ForEach-Object { Write-Host "  $_" }
}
if ($unused.Count -gt 0) {
    Write-Host ""
    Write-Host "以下 $($unused.Count) 个键在语言文件里，但静态扫描不到使用点（可能是拼接键，也可能是死键）：" -ForegroundColor DarkGray
    $unused | Select-Object -First 40 | ForEach-Object { Write-Host "  $_" }
    if ($unused.Count -gt 40) { Write-Host "  …还有 $($unused.Count - 40) 个" }
}

# 有真缺键时以非零退出，让调用方（人或 CI）必须处理，而不是当成「合并成功」。
if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "语言文件已写出，但有 $($missing.Count) 个缺键需要补齐。" -ForegroundColor Yellow
    exit 2
}
