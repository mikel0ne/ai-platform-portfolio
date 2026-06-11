# 06 — Ingress y acceso

**Prerrequisitos:** guías 02-05 (ingress-nginx, postgres, backend y frontend desplegados).
**Resultado:** la aplicación completa accesible en `http://copilot.localtest.me` y smoke test extremo a extremo superado.

## 1. Reglas de enrutado

[`k8s/ingress.yaml`](../../k8s/ingress.yaml):

| Ruta | Destino | Por qué |
|---|---|---|
| `/api` (Prefix) | `backend:8080` | toda la API (`/api/copilot/**`, `/api/llm/**`) |
| `/actuator/health` (Prefix) | `backend:8080` | comprobar salud desde fuera sin exponer todo Actuator |
| `/` (Prefix) | `frontend:80` | SPA y estáticos |

El orden importa: ingress-nginx evalúa primero los prefijos más específicos, así `/api/...` nunca cae en el frontend.

**Host `copilot.localtest.me`**: el dominio público `*.localtest.me` resuelve siempre a `127.0.0.1`, de modo que funciona sin tocar el fichero `hosts` de Windows. Alternativas anotadas en el manifiesto: `copilot.127.0.0.1.nip.io`, o añadir `127.0.0.1 copilot.local` a `C:\Windows\System32\drivers\etc\hosts`.

## 2. Aplicar y verificar el Ingress

```powershell
kubectl apply -f k8s/ingress.yaml
kubectl get ingress -n l1-copilot
# NAME      CLASS   HOSTS                  ADDRESS     PORTS
# copilot   nginx   copilot.localtest.me   localhost   80
```

## 3. Smoke test extremo a extremo

```powershell
# 1) SPA
curl.exe -s -o NUL -w "%{http_code}`n" http://copilot.localtest.me/            # 200

# 2) Salud del backend a través del Ingress
curl.exe -s http://copilot.localtest.me/actuator/health                        # {"status":"UP",...}

# 3) Usuarios de demo
curl.exe -s http://copilot.localtest.me/api/copilot/users                      # [{"id":"u-001",...}]

# 4) Conversación completa (el escenario de referencia, 2 turnos con el mismo conversationId)
curl.exe -s -X POST http://copilot.localtest.me/api/copilot/chat `
  -H "Content-Type: application/json" -H "X-User-Id: u-001" `
  -d '{\"conversationId\":\"e2e-1\",\"message\":\"Donde esta mi pedido?\"}'
# → reply preguntando por el portátil (#123) o el ratón (#456), resolved=false

curl.exe -s -X POST http://copilot.localtest.me/api/copilot/chat `
  -H "Content-Type: application/json" -H "X-User-Id: u-001" `
  -d '{\"conversationId\":\"e2e-1\",\"message\":\"El portatil\"}'
# → reply con estado IN_DELIVERY y fecha estimada, resolved=true
```

Y la prueba de fuego en el navegador: `http://copilot.localtest.me` → login como Ana García → repetir el escenario en la UI.

✅ **Listo cuando:** los 4 pasos responden como se indica y el segundo turno demuestra que el estado conversacional sobrevivió entre peticiones (mismo `conversationId`).

## Troubleshooting

| Síntoma | Causa | Arreglo |
|---|---|---|
| 404 nginx en todas las rutas | Ingress sin `ingressClassName: nginx` o controller caído | `kubectl get pods -n ingress-nginx`; `kubectl describe ingress copilot` |
| 502 Bad Gateway en `/api` | backend not ready o Service/puerto mal | `kubectl get endpoints backend -n l1-copilot` |
| SPA carga pero `/api` da 404 | regla `/api` ausente o detrás de la regla `/` en otro controller | revisar orden/paths del ingress.yaml |
| Funciona `localhost` pero no `copilot.localtest.me` | DNS corporativo/VPN bloquea localtest.me | usar `nip.io` o entrada en hosts |
| El turno 2 "olvida" el turno 1 | pods del backend reciclados entre turnos (o >1 réplica) | `kubectl get pods`: ¿reinicios? mantener `replicas: 1`; el estado es en-memoria (D-6) |

## (Opcional) TLS local

Para demo con candado: [mkcert](https://github.com/FiloSottile/mkcert) genera un certificado de `copilot.localtest.me` de confianza local; se carga como Secret TLS y se referencia en `spec.tls` del Ingress. Pasos:

```powershell
mkcert -install
mkcert copilot.localtest.me
kubectl create secret tls copilot-tls -n l1-copilot --cert=copilot.localtest.me.pem --key=copilot.localtest.me-key.pem
# y añadir al ingress.yaml:  spec.tls: [{hosts: [copilot.localtest.me], secretName: copilot-tls}]
```
