param([int]$Port = 8096)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    throw '请将 start.ps1 中的 JAVA_HOME 设置为本机 Java 17 或更高版本的目录'
}
& "$PSScriptRoot\mvnw.cmd" -B -ntp package -DskipTests
if ($LASTEXITCODE -ne 0) { throw '项目构建失败' }
New-Item -ItemType Directory -Path "$PSScriptRoot\.work\runtime" -Force | Out-Null
$runtimeJar = "$PSScriptRoot\.work\runtime\novel-agent-$([guid]::NewGuid().ToString('N')).jar"
Copy-Item -LiteralPath "$PSScriptRoot\target\novel-agent-1.0.0.jar" -Destination $runtimeJar
& "$env:JAVA_HOME\bin\java.exe" -jar $runtimeJar --spring.profiles.active=local "--server.port=$Port"
exit $LASTEXITCODE
