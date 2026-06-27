# 06 — Ingress y acceso (Traefik)

Punto de entrada: **Traefik** (incluido en k3s/Rancher Desktop) enrutando por el host `autoops.local`. El
frontend y el backend comparten host, repartidos **por path**. Traefik no bufferiza respuestas por defecto, así
que el streaming del MCP (conexión larga) fluye sin configuración extra.

| Ruta | Sirve |
|---|---|
| `autoops.local/` | Frontend Angular (chat de búsqueda) |
| `autoops.local/mcp` | Endpoint **MCP por Streamable HTTP** (lo consume el cliente MCP) |
| `autoops.local/search` | API REST del chat (la llama el frontend) |
| `autoops.local/actuator` | Health |
| `autoops.local/swagger-ui`, `/v3/api-docs` | OpenAPI |

Weaviate **no** se expone.

## 1. Traefik ya está

No hay que instalar nada: k3s trae Traefik como ingress controller por defecto, escuchando en `localhost:80`.
(No se usa `ingress-nginx`.)

## 2. El Ingress lo monta un script genérico

El Ingress se crea con el script genérico de la plataforma, parametrizado por proyecto. Las rutas que van al
backend salen del descriptor (`infra/projects/autoops.env` → `INGRESS_BACKEND_PATHS`); el resto (`/`) va al
frontend.

```bash
bash infra/scripts/setup-app-ingress.sh --project autoops
```

Genera un Ingress `autoops-app` (clase `traefik`, anotación
`traefik.ingress.kubernetes.io/router.entrypoints: web`) con las reglas por path hacia
`autoops-backend-generic-app:8080` y `autoops-frontend-generic-app:80`.

## 3. Resolución del host

Como Administrador (una vez):

```powershell
infra\scripts\add-host.ps1 -Hostname autoops.local
```

(Añade `127.0.0.1 autoops.local` al fichero hosts de Windows. `ipconfig /flushdns` si no resuelve.)

## 4. Registrar AutoOps en el cliente MCP

Con el servidor desplegado (transporte **Streamable HTTP**, con keep-alive — Spring AI 2.0.0), añadir a la
config MCP del cliente:

```jsonc
{
  "mcpServers": {
    "autoops": { "type": "http", "url": "http://autoops.local/mcp" }
  }
}
```

Para uso **local por stdio** (sin desplegar), el cliente arranca el jar:

```jsonc
{
  "mcpServers": {
    "autoops": {
      "command": "java",
      "args": ["-jar", "autoops/backend/target/autoops-mcp-0.1.0.jar", "--spring.profiles.active=local"]
    }
  }
}
```

Tras registrarlo, el cliente debe **listar** las tools (`buscar_docs`, `diagnosticar_pod`, `logs_pod`…).

## 5. Verificación end-to-end

```bash
# Health a través del Ingress (con cabecera Host si aún no tienes el hosts file)
curl -H "Host: autoops.local" http://localhost/actuator/health        # {"status":"UP"}
# Chat de búsqueda (RAG)
curl -X POST -H "Host: autoops.local" -H "Content-Type: application/json" \
  http://localhost/search --data-binary '{"consulta":"que probes debe exponer un microservicio"}'
```

Y desde Claude Code: "lista las herramientas de autoops" o invocar `buscar_docs` debe funcionar contra la URL
del Ingress.

Continuar con [07-operacion-troubleshooting.md](07-operacion-troubleshooting.md).
