# add-wiki-host.ps1
# Run as Administrator: right click -> "Run with PowerShell"
# or from an elevated terminal: .\infra\scripts\add-wiki-host.ps1
#
# Adds "wiki.local -> 127.0.0.1" to the Windows hosts file to reach
# Wiki.js without a port:
#   http://wiki.local/

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 wiki.local"

if (Select-String -Path $hostsFile -Pattern "wiki\.local" -Quiet) {
    Write-Host "[ok] 'wiki.local' is already in the hosts file." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Added: $entry" -ForegroundColor Green
}

Write-Host ""
Write-Host "Checking connectivity to wiki.local:80..."
$test = Test-NetConnection -ComputerName "wiki.local" -Port 80 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] wiki.local:80 reachable." -ForegroundColor Green
    Write-Host ""
    Write-Host "  http://wiki.local/   <- Wiki.js" -ForegroundColor Yellow
} else {
    Write-Host "[!] wiki.local:80 is not reachable." -ForegroundColor Yellow
    Write-Host "    Make sure k3s is running and you have run:" -ForegroundColor Yellow
    Write-Host "    bash infra/scripts/08-setup-wikijs.sh" -ForegroundColor Yellow
}
