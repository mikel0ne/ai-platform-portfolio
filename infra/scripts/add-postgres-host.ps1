# add-postgres-host.ps1
# Ejecutar como Administrador: clic derecho -> "Ejecutar con PowerShell"
# O desde una terminal elevada: .\infra\scripts\add-postgres-host.ps1
#
# Añade "postgres.local -> 127.0.0.1" al fichero hosts de Windows

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 postgres.local"

if (Select-String -Path $hostsFile -Pattern "postgres\.local" -Quiet) {
    Write-Host "[ok] La entrada 'postgres.local' ya existe en el fichero hosts." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Añadido: $entry" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Ahora puedes conectarte con:" -ForegroundColor Cyan
    Write-Host "  jdbc:postgresql://postgres.local:5432/appdb" -ForegroundColor Yellow
    Write-Host "  psql -h postgres.local -p 5432 -U appuser -d appdb" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Verificando conectividad a postgres.local:5432..."
$test = Test-NetConnection -ComputerName "postgres.local" -Port 5432 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] postgres.local:5432 accesible." -ForegroundColor Green
} else {
    Write-Host "[!] No se puede alcanzar postgres.local:5432. Asegúrate de que k3s está corriendo." -ForegroundColor Yellow
}
