# 06 — Ingress y acceso (Traefik)

**Prerrequisitos:** guías 02-05 (Traefik de serie, infra compartida, backend y
frontend desplegados con `generic-app`).
**Resultado:** la aplicación completa accesible en `http://l1-copilot.local` y
smoke test extremo a extremo superado.

## 1. Reglas de enrutado

El Ingress **no lo genera el chart de Helm** — se aplica directamente con el script genérico
[`infra/scripts/setup-app-ingress.sh`](../../infra/scripts/setup-app-ingress.sh) pasándole
`--project l1-copilot`. En los values del backend y del frontend `ingress.enabled: false`.

Dos paths, sin puertos adicionales, puerto 80 estándar de Traefik:

| Ruta | Destino | Por qué |
|---|---|---|
| `/api` (Prefix) | `l1-copilot-backend-generic-app:8080` | toda la API y el actuator (`/api/actuator/...`) |
| `/` (Prefix) | `l1-copilot-frontend-generic-app:80` | SPA y estáticos |

Se usa el recurso `Ingress` estándar con `ingressClassName: traefik`. Deliberadamente
**no** se usan CRDs de Traefik: el manifiesto es portable a cualquier Ingress
Controller. Traefik enruta por el prefijo más largo, así `/api/...` nunca cae en el
frontend.

**Sin `StripPrefix`**: el Ingress pasa el path tal cual. El prefijo `/api` lo
absorbe el backend con `server.servlet.context-path: /api` (ver `application.yml`).
Por eso los `@RequestMapping` no llevan `/api` y el actuator queda automáticamente
en `/api/actuator/...`. Los nombres de Service incluyen el sufijo `-generic-app`
porque es el patrón del chart Helm `generic-app` (release + chart name).

**Host `l1-copilot.local`**: requiere añadir una entrada en el fichero `hosts` de
Windows (una sola vez, como Administrador):

```powershell
# PowerShell como Administrador — o ejecutar el script:
.\infra\scripts\add-copilot-host.ps1

# Manualmente:
Add-Content C:\Windows\System32\drivers\etc\hosts "`n127.0.0.1 l1-copilot.local"
```

## 2. Aplicar el Ingress

```bash
bash infra/scripts/setup-app-ingress.sh --project l1-copilot
```

```bash
kubectl get ingress -n l1-copilot
# NAME              CLASS     HOSTS              ADDRESS      PORTS
# l1-copilot-app    traefik   l1-copilot.local   <ip-local>   80
```

## 3. Smoke test extremo a extremo

```bash
# 1) SPA
curl -s -o /dev/null -w "%{http_code}\n" http://l1-copilot.local/            # 200

# 2) Salud del backend a través del Ingress
curl -s http://l1-copilot.local/api/actuator/health                          # {"status":"UP",...}

# 3) Usuarios de demo
curl -s http://l1-copilot.local/api/copilot/users                            # [{"id":"u-001",...}]

# 4) Conversación completa (escenario de referencia, 2 turnos con el mismo conversationId)
curl -s -X POST http://l1-copilot.local/api/copilot/chat \
  -H "Content-Type: application/json" -H "X-User-Id: u-001" \
  -d '{"conversationId":"e2e-1","message":"Donde esta mi pedido?"}'
# → reply preguntando por el portátil (#123) o el ratón (#456), resolved=false

curl -s -X POST http://l1-copilot.local/api/copilot/chat \
  -H "Content-Type: application/json" -H "X-User-Id: u-001" \
  -d '{"conversationId":"e2e-1","message":"El portatil"}'
# → reply con estado IN_DELIVERY y fecha estimada, resolved=true
```

Y la prueba de fuego en el navegador: `http://l1-copilot.local` → login como Ana
García → repetir el escenario en la UI.

✅ **Listo cuando:** los 4 pasos responden como se indica y el segundo turno demuestra
que el estado conversacional sobrevivió entre peticiones (mismo `conversationId`).

## Troubleshooting

| Síntoma | Causa | Arreglo |
|---|---|---|
| 404 en todas las rutas | Ingress sin `ingressClassName: traefik` o Traefik deshabilitado en Rancher Desktop | Preferences → Kubernetes → Traefik habilitado; `kubectl get pods -n kube-system -l app.kubernetes.io/name=traefik` |
| 502/504 en `/api` | backend not ready o Service/puerto mal | `kubectl get endpoints l1-copilot-backend-generic-app -n l1-copilot` |
| SPA carga pero `/api` da 404 | Ingress no aplicado | `bash infra/scripts/setup-app-ingress.sh --project l1-copilot` |
| `l1-copilot.local` no resuelve | falta entrada en el fichero hosts | ejecutar `infra/scripts/add-copilot-host.ps1` como Administrador |
| Nada responde en el puerto 80 | otro proceso ocupa el 80 en Windows (IIS, etc.) | liberar el puerto; Traefik publica en localhost:80 |
| El turno 2 "olvida" el turno 1 | pod del backend reciclado entre turnos (o >1 réplica) | `kubectl get pods`: ¿RESTARTS? mantener `replicaCount: 1`; estado en memoria (D-8) |

## (Opcional) TLS local

[mkcert](https://github.com/FiloSottile/mkcert) genera un certificado de
`l1-copilot.local` de confianza local. Como el Ingress se gestiona con el script
(no con Helm), se aplica directamente con `kubectl`:

```bash
mkcert -install
mkcert l1-copilot.local
kubectl create secret tls copilot-tls -n l1-copilot \
  --cert=l1-copilot.local.pem --key=l1-copilot.local-key.pem

# Añadir la sección TLS al Ingress:
kubectl edit ingress l1-copilot-app -n l1-copilot
# spec:
#   tls:
#     - hosts: ["l1-copilot.local"]
#       secretName: copilot-tls
```
