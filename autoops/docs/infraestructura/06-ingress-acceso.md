# 06 — Ingress y acceso

Punto de entrada único: **NGINX Ingress Controller** enrutando por el host `autoops.local` hacia el servicio
`autoops-mcp`. Como AutoOps sirve todo desde una sola app, hay una sola regla de backend; lo importante es
configurar bien **SSE** (la conexión MCP es de larga duración y en streaming).

| Ruta | Sirve |
|---|---|
| `autoops.local/` | UI mínima del chat de búsqueda |
| `autoops.local/sse` | Endpoint **MCP por SSE** (lo consume el cliente MCP) |
| `autoops.local/search` | API REST del chat |
| `autoops.local/actuator` | Health |

Weaviate **no** se expone.

## 1. Instalar el controlador NGINX Ingress

### Docker Desktop

```powershell
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
kubectl wait --namespace ingress-nginx --for=condition=ready pod `
  --selector=app.kubernetes.io/component=controller --timeout=120s
```

El Service `ingress-nginx-controller` es `LoadBalancer`; Docker Desktop lo publica en `localhost:80/443`.

### Minikube

```powershell
minikube addons enable ingress
```

Acceso: `minikube tunnel` (publica en localhost) o la IP de `minikube ip` en `hosts`.

## 2. Regla de Ingress (con SSE)

`k8s/30-ingress.yaml`:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: autoops
  namespace: autoops
  annotations:
    # SSE: respuestas en streaming de larga duración → NO bufferizar y timeouts altos
    nginx.ingress.kubernetes.io/proxy-buffering: "off"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
spec:
  ingressClassName: nginx
  rules:
    - host: autoops.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: autoops-mcp
                port:
                  number: 8080
```

> Una sola regla: todo (`/`, `/sse`, `/search`, `/actuator`) va al mismo servicio, que enruta internamente. Las
> anotaciones de **no bufferizar + timeouts largos** son imprescindibles para que la conexión MCP por SSE no se
> corte ni se entregue a trompicones.

```powershell
kubectl apply -f k8s/30-ingress.yaml
kubectl get ingress -n autoops
```

## 3. Entrada en el fichero hosts

Como administrador, añadir a `C:\Windows\System32\drivers\etc\hosts`:

```
127.0.0.1 autoops.local        # Docker Desktop o minikube tunnel
# <minikube-ip> autoops.local  # Minikube sin tunnel (minikube ip)
```

Vaciar caché DNS si no resuelve: `ipconfig /flushdns`.

## 4. Registrar AutoOps en el cliente MCP

Esta es la prueba de fuego: que **Claude Code** (u otro cliente MCP) vea las tools.

Con transporte **HTTP/SSE** (servidor desplegado), añadir a la configuración MCP del cliente (p. ej. `.mcp.json`
del proyecto o la config global de Claude Code):

```jsonc
{
  "mcpServers": {
    "autoops": {
      "type": "sse",
      "url": "http://autoops.local/sse"
    }
  }
}
```

Para uso **local por stdio** (sin desplegar), el servidor se lanza como subproceso y el cliente lo arranca:

```jsonc
{
  "mcpServers": {
    "autoops": {
      "command": "java",
      "args": ["-jar", "target/autoops-mcp-0.1.0.jar", "--spring.profiles.active=local"]
    }
  }
}
```

Tras registrarlo, el cliente debe **listar** las tools (`buscar_docs`, `diagnosticar_pod`, `logs_pod`…).

## 5. Verificación end-to-end

```powershell
# Health a través del Ingress
Invoke-WebRequest http://autoops.local/actuator/health      # UP

# Chat de búsqueda en el navegador
Start-Process http://autoops.local
```

Y desde Claude Code: pedir "lista las herramientas de autoops" o invocar `buscar_docs` debe funcionar contra la
URL del Ingress.

## 6. TLS local (opcional)

1. Certificado de confianza con [mkcert](https://github.com/FiloSottile/mkcert): `mkcert autoops.local`.
2. Secret TLS: `kubectl create secret tls autoops-tls -n autoops --cert=autoops.local.pem --key=autoops.local-key.pem`.
3. Añadir `spec.tls` al Ingress con `secretName: autoops-tls`. (Algunos clientes MCP requieren HTTPS para SSE
   remoto; en local, mkcert lo resuelve sin avisos de certificado.)

Continuar con [07-operacion-troubleshooting.md](07-operacion-troubleshooting.md).
