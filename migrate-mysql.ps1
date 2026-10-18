param([int]$Port = 8096)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$migrationJava = 'C:\Program Files\Java\jdk-17'
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw '应用端口正在使用，迁移终止。需要结束应用写入后执行迁移。'
}
if (-not (Test-Path -LiteralPath "$PSScriptRoot\config\mysql-local.properties")) {
    throw '缺少 config/mysql-local.properties'
}
if (-not (Test-Path -LiteralPath "$PSScriptRoot\data\novel.mv.db")) {
    throw '缺少 H2 源数据文件'
}
$env:JAVA_HOME = $migrationJava
& "$PSScriptRoot\mvnw.cmd" -B -ntp compile dependency:copy-dependencies -DincludeScope=runtime -DskipTests
if ($LASTEXITCODE -ne 0) { throw '迁移工具依赖准备失败' }
& "$migrationJava\bin\java.exe" --class-path 'target/classes;target/dependency/*' tools/MigrateH2ToMySql.java
if ($LASTEXITCODE -ne 0) { throw '数据迁移失败，请查看具体错误；源文件保持只读' }
