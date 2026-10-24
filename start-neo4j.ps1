param(
    [string]$Neo4jDirectory = 'D:\Program Files\Neo4j\neo4j-community-5.26.32',
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath "$Neo4jDirectory\bin\neo4j.ps1")) { throw '未找到 Neo4j 安装目录' }
if (-not (Test-Path -LiteralPath "$JavaDirectory\bin\java.exe")) { throw '未找到 Java 17' }
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 7474,7687 })
if ($listeners.Count -gt 0) {
    $status = Invoke-RestMethod -Uri 'http://127.0.0.1:7474/' -TimeoutSec 5
    if (-not $status.neo4j_version -or -not ($listeners | Where-Object LocalPort -eq 7687)) {
        throw '7474 或 7687 端口已经使用，未确认可用的 Neo4j 服务'
    }
    Write-Output "Neo4j 已运行：$($status.neo4j_version)"
    exit 0
}
$logDirectory = 'D:\ProgramData\Neo4j\logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$env:JAVA_HOME = $JavaDirectory
$env:NEO4J_HOME = $Neo4jDirectory
$process = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',"`"$Neo4jDirectory\bin\neo4j.ps1`"",'console'
) -WorkingDirectory $Neo4jDirectory -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$logDirectory\console.stdout.log" -RedirectStandardError "$logDirectory\console.stderr.log"
Write-Output "Neo4j 后台启动进程：$($process.Id)，日志目录：$logDirectory"
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if ($process.HasExited) { throw "Neo4j 进程已经退出，请查看 $logDirectory" }
    $ready = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 7474,7687 })
    if (($ready | Where-Object LocalPort -eq 7474) -and ($ready | Where-Object LocalPort -eq 7687)) {
        $status = Invoke-RestMethod -Uri 'http://127.0.0.1:7474/' -TimeoutSec 5
        if (-not $status.neo4j_version) { throw 'Neo4j HTTP 接口未返回版本信息' }
        Write-Output "Neo4j 已启动：$($status.neo4j_version)"
        exit 0
    }
    Start-Sleep -Seconds 1
}
throw "Neo4j 启动超时，请查看 $logDirectory"
