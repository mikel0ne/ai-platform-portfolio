# add-wiki-host.ps1
# Ejecutar como Administrador: clic derecho -> "Ejecutar con PowerShell"
# O desde una terminal elevada: .\infra\scripts\add-wiki-host.ps1
#
# Añade "wiki.local -> 127.0.0.1" al fichero hosts de Windows para acceder a
# Wiki.js sin puerto raro:
#   http://wiki.local/

$hostsFile = "C:\Windows\System32\drivers\etc\hosts"
$entry     = "127.0.0.1 wiki.local"

if (Select-String -Path $hostsFile -Pattern "wiki\.local" -Quiet) {
    Write-Host "[ok] La entrada 'wiki.local' ya existe en el fichero hosts." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Añadido: $entry" -ForegroundColor Green
}

Write-Host ""
Write-Host "Verificando conectividad a wiki.local:80..."
$test = Test-NetConnection -ComputerName "wiki.local" -Port 80 -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] wiki.local:80 accesible." -ForegroundColor Green
    Write-Host ""
    Write-Host "  http://wiki.local/   <- Wiki.js" -ForegroundColor Yellow
} else {
    Write-Host "[!] No se puede alcanzar wiki.local:80." -ForegroundColor Yellow
    Write-Host "    Asegurate de que k3s esta corriendo y has ejecutado:" -ForegroundColor Yellow
    Write-Host "    bash infra/scripts/08-setup-wikijs.sh" -ForegroundColor Yellow
}
