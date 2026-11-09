# add-copilot-host.ps1
# Ejecutar como Administrador: clic derecho -> "Ejecutar con PowerShell"
# O desde una terminal elevada: .\infra\scripts\add-copilot-host.ps1
#
# Añade "l1-copilot.local -> 127.0.0.1" al fichero hosts de Windows
# para acceder a la app sin puerto raro:
#   http://l1-copilot.local/          <- Frontend Angular
#   http://l1-copilot.local/api       <- REST API
#   http://l1-copilot.local/actuator  <- Actuator

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 l1-copilot.local"

if (Select-String -Path $hostsFile -Pattern "l1-copilot\.local" -Quiet) {
    Write-Host "[ok] La entrada 'l1-copilot.local' ya existe en el fichero hosts." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Añadido: $entry" -ForegroundColor Green
}

Write-Host ""
Write-Host "Verificando conectividad a l1-copilot.local:80..."
$test = Test-NetConnection -ComputerName "l1-copilot.local" -Port 80 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] l1-copilot.local:80 accesible." -ForegroundColor Green
    Write-Host ""
    Write-Host "  URLs de la aplicacion:" -ForegroundColor Cyan
    Write-Host "  http://l1-copilot.local/                       <- Angular frontend" -ForegroundColor Yellow
    Write-Host "  http://l1-copilot.local/api                    <- REST API" -ForegroundColor Yellow
    Write-Host "  http://l1-copilot.local/api/swagger-ui/index.html  <- Swagger UI" -ForegroundColor Yellow
    Write-Host "  http://l1-copilot.local/api/actuator/health    <- Health check" -ForegroundColor Yellow
} else {
    Write-Host "[!] No se puede alcanzar l1-copilot.local:80." -ForegroundColor Yellow
    Write-Host "    Asegurate de que k3s esta corriendo y has ejecutado:" -ForegroundColor Yellow
    Write-Host "    bash infra/scripts/setup-app-ingress.sh --project l1-copilot" -ForegroundColor Yellow
}
