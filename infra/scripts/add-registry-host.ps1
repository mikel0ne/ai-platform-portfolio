# add-registry-host.ps1
# Ejecutar como Administrador: clic derecho -> "Ejecutar con PowerShell"
# O desde una terminal elevada: .\infra\scripts\add-registry-host.ps1
#
# Añade "registry.local -> 127.0.0.1" al fichero hosts de Windows.
# El registry se sirve vía Traefik en registry.local SIN puerto (80/443);
# el daemon.json (insecure-registries) y el registries.yaml de k3s ya los
# configura infra/scripts/05-setup-registry-ingress.sh dentro de la WSL
# rancher-desktop — este script NO toca daemon.json.

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 registry.local"

if (Select-String -Path $hostsFile -Pattern "registry\.local" -Quiet) {
    Write-Host "[ok] La entrada 'registry.local' ya existe en el fichero hosts." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Añadido: $entry" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Ahora puedes usar el registry con:" -ForegroundColor Cyan
    Write-Host "  docker tag  mi-imagen:tag  registry.local/mi-imagen:tag" -ForegroundColor Yellow
    Write-Host "  docker push registry.local/mi-imagen:tag" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Verificando conectividad a registry.local:80..."
$test = Test-NetConnection -ComputerName "registry.local" -Port 80 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] registry.local:80 accesible." -ForegroundColor Green
} else {
    Write-Host "[!] No se puede alcanzar registry.local:80. Asegúrate de que k3s está corriendo y has ejecutado infra/scripts/05-setup-registry-ingress.sh." -ForegroundColor Yellow
}
