# add-registry-host.ps1
# Ejecutar como Administrador: clic derecho -> "Ejecutar con PowerShell"
# O desde una terminal elevada: .\scripts\add-registry-host.ps1
#
# Añade "registry.local -> 127.0.0.1" al fichero hosts de Windows

$hostsFile    = "C:\Windows\System32\drivers\etc\hosts"
$entry        = "127.0.0.1 registry.local"
$registryPort = 15000

if (Select-String -Path $hostsFile -Pattern "registry\.local" -Quiet) {
    Write-Host "[ok] La entrada 'registry.local' ya existe en el fichero hosts." -ForegroundColor Green
} else {
    Add-Content -Path $hostsFile -Value "`n$entry"
    Write-Host "[ok] Añadido: $entry" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Ahora puedes usar el registry con:" -ForegroundColor Cyan
    Write-Host "  docker tag  mi-imagen:tag  registry.local:${registryPort}/mi-imagen:tag" -ForegroundColor Yellow
    Write-Host "  docker push registry.local:${registryPort}/mi-imagen:tag" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Verificando conectividad a registry.local:${registryPort}..."
$test = Test-NetConnection -ComputerName "registry.local" -Port $registryPort -WarningAction SilentlyContinue
if ($test.TcpTestSucceeded) {
    Write-Host "[ok] registry.local:${registryPort} accesible." -ForegroundColor Green
} else {
    Write-Host "[!] No se puede alcanzar registry.local:${registryPort}. Asegúrate de que k3s está corriendo y has ejecutado 05-setup-registry-ingress.sh." -ForegroundColor Yellow
}

# Verificar que el daemon.json de Docker incluye registry.local como inseguro
Write-Host ""
Write-Host "Verificando daemon.json de Docker para registry inseguro..."
$daemonJson = "$env:USERPROFILE\.docker\daemon.json"
$insecureEntry = "registry.local:${registryPort}"

if (Test-Path $daemonJson) {
    $config = Get-Content $daemonJson | ConvertFrom-Json
    if ($config.'insecure-registries' -and ($config.'insecure-registries' -contains $insecureEntry)) {
        Write-Host "[ok] '$insecureEntry' ya está en insecure-registries." -ForegroundColor Green
    } else {
        if (-not $config.'insecure-registries') {
            $config | Add-Member -MemberType NoteProperty -Name 'insecure-registries' -Value @($insecureEntry)
        } else {
            $config.'insecure-registries' += $insecureEntry
        }
        $config | ConvertTo-Json -Depth 10 | Set-Content $daemonJson
        Write-Host "[ok] Añadido '$insecureEntry' a insecure-registries en daemon.json." -ForegroundColor Green
        Write-Host "[!]  Reinicia Docker Desktop / Rancher Desktop para aplicar el cambio." -ForegroundColor Yellow
    }
} else {
    Write-Host "[!] daemon.json no encontrado en $daemonJson." -ForegroundColor Yellow
    Write-Host "    Ejecuta primero infra/scripts/02-setup-registry.sh o añade manualmente:" -ForegroundColor Yellow
    Write-Host '    { "insecure-registries": ["registry.local:'"${registryPort}"'"] }' -ForegroundColor Yellow
}
