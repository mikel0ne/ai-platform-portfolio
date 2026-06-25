# 01 — Prerrequisitos

## Herramientas necesarias

| Herramienta | Versión mínima | Para qué | Verificación |
|---|---|---|---|
| Docker Desktop | 4.30+ | Construir la imagen y (opcionalmente) el clúster K8s | `docker version` |
| kubectl | 1.29+ | Operar el clúster | `kubectl version --client` |
| Java (Temurin) | 17 (recomendado 21) | Compilar `autoops-mcp` | `java -version` |
| Maven | 3.9+ | Build del servidor | `mvn -version` |
| Git | 2.40+ | Código fuente | `git --version` |
| Un cliente MCP | — | Probar el servidor (Claude Code, GitHub Copilot…) | — |

Opcionales pero recomendadas:

- **k9s** — TUI para inspeccionar el clúster cómodamente.
- **Helm 3** — solo para instalar el controlador NGINX Ingress en Minikube (en Docker Desktop basta un
  `kubectl apply`).

> Ya **no** hace falta Node.js: no hay frontend Angular. El chat de búsqueda es una UI estática mínima servida
> por la propia app.

## Elección del clúster local

Dos opciones soportadas; elegir una y ser consistente.

### Opción A — Kubernetes de Docker Desktop (recomendada en Windows)

1. Docker Desktop → *Settings* → *Kubernetes* → **Enable Kubernetes** → *Apply & Restart*.
2. Verificar contexto:

```powershell
kubectl config use-context docker-desktop
kubectl get nodes
```

**Ventaja:** las imágenes de `docker build` son visibles directamente por el clúster (mismo daemon); no hay paso
de carga.

**Recursos:** asignar a Docker Desktop al menos **6 GB de RAM y 4 CPUs** (*Settings → Resources*). El stack
(`autoops-mcp` + Weaviate + ingress) consume ~2-3 GB; el reranker opcional añade ~1 GB.

### Opción B — Minikube

```powershell
winget install Kubernetes.minikube
minikube start --cpus=4 --memory=8192 --driver=docker
kubectl config use-context minikube
```

**Diferencia:** Minikube tiene su propio daemon Docker. Tras cada `docker build` hay que cargar la imagen
(`minikube image load autoops/mcp:0.1.0`) o construir dentro de su daemon (`minikube docker-env | Invoke-Expression`).

## Resolución de nombres local

El Ingress enruta por el host `autoops.local`. Añadir al fichero `hosts` (como administrador):

- **Windows:** `C:\Windows\System32\drivers\etc\hosts`
- **Linux/macOS:** `/etc/hosts`

```
127.0.0.1 autoops.local
```

> Con Minikube, usar la IP de `minikube ip` (salvo `minikube tunnel`, que publica en localhost). Ver
> [06-ingress-acceso.md](06-ingress-acceso.md).

## Credenciales del proveedor LLM

AutoOps usa un proveedor LLM/embeddings **abstraído**; por defecto **Google AI Studio (Gemini, capa gratuita)**.
El LLM es de **uso interno** (reformular en el RAG correctivo y redactar el chat); el razonamiento de cara al
usuario lo pone el cliente MCP.

1. Crear API key gratuita en <https://aistudio.google.com/app/apikey>.
2. Se inyectará como Secret de Kubernetes en [02-topologia-kubernetes.md](02-topologia-kubernetes.md). **Nunca**
   se commitea al repositorio.

## Checklist final

```powershell
docker version          # Cliente y servidor responden
kubectl get nodes       # Un nodo Ready
java -version           # 17+
mvn -version            # 3.9+
```

Con todo en verde, continuar con [02-topologia-kubernetes.md](02-topologia-kubernetes.md).
