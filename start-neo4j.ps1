param(
    [string]$Neo4jDirectory = 'D:\Program Files\Neo4j\neo4j-community-5.26.32',
    [string]$JavaDirectory = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath "$Neo4jDirectory\bin\neo4j.ps1")) { throw '未找到 Neo4j 安装目录' }
if (-not (Test-Path -LiteralPath "$JavaDirectory\bin\java.exe")) { throw '未找到 Java 17' }
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 7474,7687 })
if ($listeners.Count -gt 0) { throw '7474 或 7687 端口已经使用，请检查现有 Neo4j 进程' }
$logDirectory = 'D:\ProgramData\Neo4j\logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$env:JAVA_HOME = $JavaDirectory
$env:NEO4J_HOME = $Neo4jDirectory
$process = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',"`"$Neo4jDirectory\bin\neo4j.ps1`"",'console'
) -WorkingDirectory $Neo4jDirectory -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$logDirectory\console.stdout.log" -RedirectStandardError "$logDirectory\console.stderr.log"
Write-Output "Neo4j 后台启动进程：$($process.Id)，日志目录：$logDirectory"
