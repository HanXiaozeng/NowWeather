<#
.SYNOPSIS
    极简 RCON 客户端（Source RCON 协议），用于给开发用服务端发命令。

.DESCRIPTION
    只依赖 .NET 的 TcpClient，不需要额外工具。
    协议：每个包 = [长度:int32LE][请求id:int32LE][类型:int32LE][正文:以 \0 结尾的 UTF-8]，长度不含自身 4 字节。

.EXAMPLE
    pwsh -File tools/rcon.ps1 -Command "nowweather status"
    pwsh -File tools/rcon.ps1 -Command "nowweather test all" -TimeoutSec 120
#>
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [string]$Host_ = '127.0.0.1',
    [int]$Port = 25575,
    [string]$Password = 'nowweather-test',
    [int]$TimeoutSec = 60
)

$ErrorActionPreference = 'Stop'

function Read-Exact {
    param([System.IO.Stream]$Stream, [int]$Count)
    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw "连接被对端关闭" }
        $offset += $read
    }
    return $buffer
}

function Send-Packet {
    param([System.IO.Stream]$Stream, [int]$Id, [int]$Type, [string]$Body)
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $length = 4 + 4 + $bodyBytes.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$length)
    $bw.Write([int]$Id)
    $bw.Write([int]$Type)
    $bw.Write($bodyBytes)
    $bw.Write([byte]0)
    $bw.Write([byte]0)
    $bw.Flush()
    $bytes = $ms.ToArray()
    $Stream.Write($bytes, 0, $bytes.Length)
    $Stream.Flush()
    $bw.Dispose(); $ms.Dispose()
}

function Receive-Packet {
    param([System.IO.Stream]$Stream)
    $lenBytes = Read-Exact -Stream $Stream -Count 4
    $length = [System.BitConverter]::ToInt32($lenBytes, 0)
    if ($length -lt 10 -or $length -gt 4 * 1024 * 1024) { throw "响应长度异常: $length" }
    $payload = Read-Exact -Stream $Stream -Count $length
    $id = [System.BitConverter]::ToInt32($payload, 0)
    $type = [System.BitConverter]::ToInt32($payload, 4)
    $body = [System.Text.Encoding]::UTF8.GetString($payload, 8, $length - 10)
    return [pscustomobject]@{ Id = $id; Type = $type; Body = $body }
}

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Host_, $Port)
$stream = $client.GetStream()
$stream.ReadTimeout = $TimeoutSec * 1000

try {
    # 认证
    Send-Packet -Stream $stream -Id 1 -Type 3 -Body $Password
    $auth = Receive-Packet -Stream $stream
    if ($auth.Id -eq -1) { throw "RCON 认证失败（密码错误？）" }

    # 执行命令
    Send-Packet -Stream $stream -Id 2 -Type 2 -Body $Command
    $response = Receive-Packet -Stream $stream
    Write-Output $response.Body
} finally {
    $stream.Dispose()
    $client.Dispose()
}
