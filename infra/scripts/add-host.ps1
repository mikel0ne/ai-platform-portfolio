# add-host.ps1: adds "<Hostname> -> 127.0.0.1" to the Windows hosts file.
# Generic version of the add-<project>-host.ps1 helpers.
#
# Run as Administrator. Examples:
#   .\infra\scripts\add-host.ps1 -Hostname autoops.local
#   .\infra\scripts\add-host.ps1 -Hostname grafana.local
#   .\infra\scripts\add-host.ps1 -Hostname l1-copilot.local
param(
    [Parameter(Mandatory = $true)]
    [string]$Hostname,
    [int]$Port = 80
)

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 $Hostname"
$pattern   = [regex]::Escape($Hostname)

if (Select-String -Path $hostsFile -Pattern "\s$pattern(\s|$)" -Quiet) {
    Write-Host "[ok] '$Hostname' is already in the hosts file." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Added: $entry" -ForegroundColor Green
}

Write-Host ""
Write-Host "Checking connectivity to ${Hostname}:${Port}..."
$test = Test-NetConnection -ComputerName $Hostname -Port $Port -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] ${Hostname}:${Port} reachable." -ForegroundColor Green
} else {
    Write-Host "[!] ${Hostname}:${Port} is not reachable yet." -ForegroundColor Yellow
    Write-Host "    Make sure k3s is running and the Ingress is applied." -ForegroundColor Yellow
}
