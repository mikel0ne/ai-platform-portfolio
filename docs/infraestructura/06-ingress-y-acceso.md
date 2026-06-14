# 06 — Ingress y acceso (Traefik)

**Prerrequisitos:** guías 02-05 (Traefik de serie, infra compartida, backend y
frontend desplegados con `generic-app`).
**Resultado:** la aplicación completa accesible en `http://copilot.localtest.me` y
smoke test extremo a extremo superado.

## 1. Reglas de enrutado

El Ingress lo **genera el chart del backend** a partir del values
[`infra/values-l1-copilot-backend.yaml`](../../infra/values-l1-copilot-backend.yaml):

```yaml
ingress:
  enabled: true
  className: traefik
  host: copilot.localtest.me
  paths:
    - { path: /api,            pathType: Prefix }
    - { path: /actuator/health, pathType: Prefix }
ingressExtraPaths:
  - { path: /, pathType: Prefix, serviceName: l1-copilot-frontend, servicePort: http }
```

| Ruta | Destino | Por qué |
|---|---|---|
| `/api` (Prefix) | `l1-copilot-backend:8080` | toda la API (`/api/copilot/**`, `/api/llm/**`) |
| `/actuator/health` (Prefix) | `l1-copilot-backend:8080` | salud desde fuera sin exponer todo Actuator |
| `/` (Prefix) | `l1-copilot-frontend:80` | SPA y estáticos |

Se usa el recurso `Ingress` estándar con `ingressClassName: traefik`. Deliberadamente
**no** se usan CRDs de Traefik: el manifiesto es portable a cualquier Ingress
Controller. Traefik enruta por el prefijo más largo, así `/api/...` nunca cae en el
frontend.

**Host `copilot.localtest.me`**: `*.localtest.me` resuelve siempre a `127.0.0.1`, sin
tocar el fichero `hosts` de Windows. Alternativas: `copilot.127.0.0.1.nip.io` o
entrada manual en `C:\Windows\System32\drivers\etc\hosts`.

## 2. Verificar que existe

El Ingress nace cuando se despliega el backend (guía 04):

```bash
kubectl get ingress -n l1-copilot
# NAME                  CLASS     HOSTS                  ADDRESS      PORTS
# l1-copilot-backend    traefik   copilot.localtest.me   <ip-local>   80
```

## 3. Smoke test extremo a extremo

```bash
# 1) SPA
curl -s -o /dev/null -w "%{http_code}\n" http://copilot.localtest.me/            # 200

# 2) Salud del backend a través del Ingress
curl -s http://copilot.localtest.me/actuator/health                              # {"status":"UP",...}

# 3) Usuarios de demo
curl -s http://copilot.localtest.me/api/copilot/users                            # [{"id":"u-001",...}]

# 4) Conversación completa (escenario de referencia, 2 turnos con el mismo conversationId)
curl -s -X POST http://copilot.localtest.me/api/copilot/chat \
  -H "Content-Type: application/json" -H "X-User-Id: u-001" \
  -d '{"conversationId":"e2e-1","message":"Donde esta mi pedido?"}'
# → reply preguntando por el portátil (#123) o el ratón (#456), resolved=false

curl -s -X POST http://copilot.localtest.me/api/copilot/chat \
  -H "Content-Type: application/json" -H "X-User-Id: u-001" \
  -d '{"conversationId":"e2e-1","message":"El portatil"}'
# → reply con estado IN_DELIVERY y fecha estimada, resolved=true
```

Y la prueba de fuego en el navegador: `http://copilot.localtest.me` → login como Ana
García → repetir el escenario en la UI.

✅ **Listo cuando:** los 4 pasos responden como se indica y el segundo turno demuestra
que el estado conversacional sobrevivió entre peticiones (mismo `conversationId`).

## Troubleshooting

| Síntoma | Causa | Arreglo |
|---|---|---|
| 404 en todas las rutas | Ingress sin `ingressClassName: traefik` o Traefik deshabilitado en Rancher Desktop | Preferences → Kubernetes → Traefik habilitado; `kubectl get pods -n kube-system -l app.kubernetes.io/name=traefik` |
| 502/504 en `/api` | backend not ready o Service/puerto mal | `kubectl get endpoints l1-copilot-backend -n l1-copilot` |
| SPA carga pero `/api` da 404 | el Ingress se generó sin `paths` del backend | revisar `ingress.paths` en values-l1-copilot-backend.yaml y `helm upgrade` |
| Funciona `localhost` pero no `copilot.localtest.me` | DNS corporativo/VPN bloquea localtest.me | usar `nip.io` o entrada en hosts |
| Nada responde en el puerto 80 | otro proceso ocupa el 80 en Windows (IIS, etc.) | liberar el puerto; Traefik publica en localhost:80 |
| El turno 2 "olvida" el turno 1 | pod del backend reciclado entre turnos (o >1 réplica) | `kubectl get pods`: ¿RESTARTS? mantener `replicaCount: 1`; estado en memoria (D-8) |

## (Opcional) TLS local

[mkcert](https://github.com/FiloSottile/mkcert) genera un certificado de
`copilot.localtest.me` de confianza local; se carga como Secret TLS y se referencia en
los values del backend (`ingress.tls.enabled: true` + `ingress.tls.secretName`):

```bash
mkcert -install
mkcert copilot.localtest.me
kubectl create secret tls copilot-tls -n l1-copilot \
  --cert=copilot.localtest.me.pem --key=copilot.localtest.me-key.pem
# y en values-l1-copilot-backend.yaml:
#   ingress:
#     tls:
#       enabled: true
#       secretName: copilot-tls
```
