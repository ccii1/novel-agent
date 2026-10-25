param([int]$Port = 8096,[ValidateSet('h2','mysql')][string]$Database = 'h2')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "后端端口已被占用：$Port"
}
if ($Database -eq 'mysql' -and -not (Test-Path -LiteralPath "$PSScriptRoot\config\mysql-local.properties")) {
    throw '请配置本机 config/mysql-local.properties，并完成已有数据迁移'
}
$profiles = if ($Database -eq 'mysql') { 'local,mysql' } else { 'local' }
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
if ($Database -eq 'mysql') {
    & "$PSScriptRoot\start-mysql.ps1"
}
if ([string]::IsNullOrWhiteSpace($env:NOVEL_NEO4J_ENABLED)) { $env:NOVEL_NEO4J_ENABLED = 'true' }
if ($env:NOVEL_NEO4J_ENABLED -eq 'true') {
    & "$PSScriptRoot\start-neo4j.ps1"
    if ($LASTEXITCODE -ne 0) { throw 'Neo4j 启动失败' }
}
if ([string]::IsNullOrWhiteSpace($env:NOVEL_REDIS_ENABLED)) { $env:NOVEL_REDIS_ENABLED = 'true' }
if ($env:NOVEL_REDIS_ENABLED -eq 'true' -and [string]::IsNullOrWhiteSpace($env:NOVEL_REDIS_ADDRESS)) {
    & "$PSScriptRoot\start-redis.ps1"
    if ($LASTEXITCODE -ne 0) { throw 'Redis 启动失败' }
}
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    throw '请将 start.ps1 中的 JAVA_HOME 设置为本机 Java 17 或更高版本的目录'
}
& "$PSScriptRoot\mvnw.cmd" -B -ntp package -DskipTests
if ($LASTEXITCODE -ne 0) { throw '项目构建失败' }
New-Item -ItemType Directory -Path "$PSScriptRoot\.work\runtime" -Force | Out-Null
$runtimeJar = "$PSScriptRoot\.work\runtime\novel-agent.jar"
Copy-Item -LiteralPath "$PSScriptRoot\target\novel-agent.jar" -Destination $runtimeJar
& "$env:JAVA_HOME\bin\java.exe" -jar $runtimeJar "--spring.profiles.active=$profiles" "--server.port=$Port"
exit $LASTEXITCODE
