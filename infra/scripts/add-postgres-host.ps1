# add-postgres-host.ps1
# Run as Administrator: right click -> "Run with PowerShell"
# or from an elevated terminal: .\infra\scripts\add-postgres-host.ps1
#
# Adds "postgres.local -> 127.0.0.1" to the Windows hosts file

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 postgres.local"

if (Select-String -Path $hostsFile -Pattern "postgres\.local" -Quiet) {
    Write-Host "[ok] 'postgres.local' is already in the hosts file." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Added: $entry" -ForegroundColor Green
    Write-Host ""
    Write-Host "  You can now connect with:" -ForegroundColor Cyan
    Write-Host "  jdbc:postgresql://postgres.local:5432/appdb" -ForegroundColor Yellow
    Write-Host "  psql -h postgres.local -p 5432 -U appuser -d appdb" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Checking connectivity to postgres.local:5432..."
$test = Test-NetConnection -ComputerName "postgres.local" -Port 5432 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] postgres.local:5432 reachable." -ForegroundColor Green
} else {
    Write-Host "[!] postgres.local:5432 is not reachable. Make sure k3s is running." -ForegroundColor Yellow
}
