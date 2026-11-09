# add-registry-host.ps1
# Run as Administrator: right click -> "Run with PowerShell"
# or from an elevated terminal: .\infra\scripts\add-registry-host.ps1
#
# Adds "registry.local -> 127.0.0.1" to the Windows hosts file.
# The registry is served by Traefik at registry.local without a port (80/443);
# daemon.json (insecure-registries) and the k3s registries.yaml are configured by
# infra/scripts/05-setup-registry-ingress.sh inside the rancher-desktop WSL distro,
# so this script does not touch daemon.json.

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 registry.local"

if (Select-String -Path $hostsFile -Pattern "registry\.local" -Quiet) {
    Write-Host "[ok] 'registry.local' is already in the hosts file." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Added: $entry" -ForegroundColor Green
    Write-Host ""
    Write-Host "  You can now use the registry with:" -ForegroundColor Cyan
    Write-Host "  docker tag  my-image:tag  registry.local/my-image:tag" -ForegroundColor Yellow
    Write-Host "  docker push registry.local/my-image:tag" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Checking connectivity to registry.local:80..."
$test = Test-NetConnection -ComputerName "registry.local" -Port 80 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] registry.local:80 reachable." -ForegroundColor Green
} else {
    Write-Host "[!] registry.local:80 is not reachable. Make sure k3s is running and infra/scripts/05-setup-registry-ingress.sh has run." -ForegroundColor Yellow
}
