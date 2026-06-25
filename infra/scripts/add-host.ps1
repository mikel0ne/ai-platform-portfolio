# add-host.ps1 — añade "<Hostname> -> 127.0.0.1" al fichero hosts de Windows.
# GENÉRICO: sustituye a los add-<proyecto>-host.ps1 específicos.
#
# Ejecutar como Administrador. Ejemplos:
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
    Write-Host "[ok] La entrada '$Hostname' ya existe en el fichero hosts." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Anadido: $entry" -ForegroundColor Green
}

Write-Host ""
Write-Host "Verificando conectividad a ${Hostname}:${Port}..."
$test = Test-NetConnection -ComputerName $Hostname -Port $Port -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] ${Hostname}:${Port} accesible." -ForegroundColor Green
} else {
    Write-Host "[!] No se puede alcanzar ${Hostname}:${Port} todavia." -ForegroundColor Yellow
    Write-Host "    Asegurate de que k3s esta corriendo y el Ingress aplicado." -ForegroundColor Yellow
}
