param([int]$Port = 6379)
$ErrorActionPreference = 'Stop'
$redisDirectory = 'D:\Program Files\Redis-x64-5.0.14.1'
$redisServer = Join-Path $redisDirectory 'redis-server.exe'
$redisCli = Join-Path $redisDirectory 'redis-cli.exe'
if (-not (Test-Path -LiteralPath $redisServer)) { throw "Redis 程序不存在：$redisServer" }
$current = & $redisCli -h 127.0.0.1 -p $Port ping 2>$null
if ($LASTEXITCODE -eq 0 -and $current -eq 'PONG') {
    Write-Output "Redis 已运行：127.0.0.1:$Port"
    exit 0
}
$dataDirectory = Join-Path $PSScriptRoot 'data\redis'
New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null
$redisLog = Join-Path $dataDirectory 'redis.log'
Start-Process -FilePath $redisServer -WindowStyle Hidden -ArgumentList @(
    '--bind','127.0.0.1','--port',"$Port",'--dir',"`"$dataDirectory`"",'--appendonly','yes','--logfile',"`"$redisLog`""
)
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Milliseconds 200
    $response = & $redisCli -h 127.0.0.1 -p $Port ping 2>$null
    if ($LASTEXITCODE -eq 0 -and $response -eq 'PONG') {
        Write-Output "Redis 已启动：127.0.0.1:$Port；数据目录：$dataDirectory"
        exit 0
    }
}
throw "Redis 启动失败，请查看 $redisLog"
