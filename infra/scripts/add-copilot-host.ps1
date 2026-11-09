# add-copilot-host.ps1
# Run as Administrator: right click -> "Run with PowerShell"
# or from an elevated terminal: .\infra\scripts\add-copilot-host.ps1
#
# Adds "l1-copilot.local -> 127.0.0.1" to the Windows hosts file
# to reach the app without a port:
#   http://l1-copilot.local/          <- Frontend Angular
#   http://l1-copilot.local/api       <- REST API
#   http://l1-copilot.local/actuator  <- Actuator

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 l1-copilot.local"

if (Select-String -Path $hostsFile -Pattern "l1-copilot\.local" -Quiet) {
    Write-Host "[ok] 'l1-copilot.local' is already in the hosts file." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Added: $entry" -ForegroundColor Green
}

Write-Host ""
Write-Host "Checking connectivity to l1-copilot.local:80..."
$test = Test-NetConnection -ComputerName "l1-copilot.local" -Port 80 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] l1-copilot.local:80 reachable." -ForegroundColor Green
    Write-Host ""
    Write-Host "  Application URLs:" -ForegroundColor Cyan
    Write-Host "  http://l1-copilot.local/                       <- Angular frontend" -ForegroundColor Yellow
    Write-Host "  http://l1-copilot.local/api                    <- REST API" -ForegroundColor Yellow
    Write-Host "  http://l1-copilot.local/api/swagger-ui/index.html  <- Swagger UI" -ForegroundColor Yellow
    Write-Host "  http://l1-copilot.local/api/actuator/health    <- Health check" -ForegroundColor Yellow
} else {
    Write-Host "[!] l1-copilot.local:80 is not reachable." -ForegroundColor Yellow
    Write-Host "    Make sure k3s is running and you have run:" -ForegroundColor Yellow
    Write-Host "    bash infra/scripts/setup-app-ingress.sh --project l1-copilot" -ForegroundColor Yellow
}
