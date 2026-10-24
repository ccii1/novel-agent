param([string]$ServiceName = 'MySQL')
$ErrorActionPreference = 'Stop'
$service = Get-Service -Name $ServiceName
if ($service.Status -ne 'Running') {
    Start-Service -Name $ServiceName
    $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
}
Write-Output "MySQL 服务已运行：$ServiceName"
