# 01 — Prerrequisitos

## Herramientas necesarias

| Herramienta | Versión mínima | Para qué | Verificación |
|---|---|---|---|
| Rancher Desktop | 1.16+ | Clúster k3s, `docker`, `kubectl` y `helm` | `docker version` |
| kubectl | 1.29+ | Operar el clúster | `kubectl version --client` |
| Java (Temurin) | 17 (recomendado 21) | Compilar `autoops-mcp` | `java -version` |
| Maven | 3.9+ (el repo trae el wrapper `./mvnw`) | Build del servidor; antes hay que instalar `platform-commons` (`./mvnw -f ../../platform-commons/pom.xml install`) | `./mvnw -version` |
| Git | 2.40+ | Código fuente | `git --version` |
| Un cliente MCP | — | Probar el servidor (Claude Code, GitHub Copilot…) | — |

Opcionales pero recomendadas:

- **k9s** — TUI para inspeccionar el clúster cómodamente.
- **Helm 3** — viene con Rancher Desktop; lo usa `deploy.sh` y el stack de observabilidad.

> **Node.js 24 hace falta** (el Dockerfile del frontend usa `node:24-alpine`): AutoOps tiene un **frontend Angular separado** (`autoops/frontend/`,
> chat de búsqueda) que se construye con `npm` y se sirve por nginx en su propia imagen.

> **Clúster real:** el portfolio usa **Rancher Desktop (k3s)** con ingress **Traefik** (no Docker
> Desktop + nginx-ingress). El registry local es `registry.local`. Ver `infra/INFRA.md`.

## Clúster local

El clúster es **Rancher Desktop (k3s)**; su instalación y la plataforma compartida (registry, Postgres, Weaviate,
observabilidad) están en [`infra/INFRA.md`](../../../infra/INFRA.md) y en las guías de infraestructura de L1
Copilot (`02-cluster-local.md`).

```powershell
kubectl config use-context rancher-desktop
kubectl get nodes
```

**Recursos:** asignar a la VM al menos **6 GB de RAM y 4 CPUs**. El stack (`autoops-backend` + Weaviate + reranker +
ingress) consume ~2-3 GB.

## Resolución de nombres local

El Ingress enruta por el host `autoops.local`. Añadir al fichero `hosts` (como administrador):

- **Windows:** `C:\Windows\System32\drivers\etc\hosts`
- **Linux/macOS:** `/etc/hosts`

```
127.0.0.1 autoops.local
```

> `infra/scripts/add-host.ps1 -Hostname autoops.local` lo hace por ti. Ver [06-ingress-acceso.md](06-ingress-acceso.md).

## Credenciales del proveedor LLM

AutoOps usa un proveedor LLM/embeddings **abstraído**; por defecto **Google AI Studio (Gemini, capa gratuita)**.
El LLM es de **uso interno** (variantes, calificación y reformulación en el RAG correctivo, y redacción del chat); el razonamiento de cara al
usuario lo pone el cliente MCP.

1. Crear API key gratuita en <https://aistudio.google.com/app/apikey>.
2. Se guarda en `infra/.env.local` (gitignored) y `deploy.sh` la sincroniza como Secret de Kubernetes
   ([02-topologia-kubernetes.md](02-topologia-kubernetes.md)). **Nunca** se commitea al repositorio.

## Checklist final

```powershell
docker version          # Cliente y servidor responden
kubectl get nodes       # Un nodo Ready
java -version           # 17+
./mvnw -version         # 3.9+ (wrapper del repo)
```

Con todo en verde, continuar con [02-topologia-kubernetes.md](02-topologia-kubernetes.md).
