# 02 — Clúster local (Docker Desktop + Kubernetes)

**Prerrequisitos:** Windows 11, Docker Desktop instalado.
**Resultado:** clúster funcionando con ingress-nginx y el namespace `l1-copilot`.

## 1. Activar Kubernetes en Docker Desktop

1. Docker Desktop → ⚙️ **Settings** → **Kubernetes** → marcar **Enable Kubernetes** → **Apply & Restart**.
2. Esperar a que el icono de Kubernetes quede en verde (la primera vez descarga imágenes, varios minutos).
3. Recursos recomendados: **Settings → Resources** → ≥ 6 GB RAM, ≥ 4 CPUs.

Verificación:

```powershell
kubectl config use-context docker-desktop
kubectl get nodes
# NAME             STATUS   ROLES           AGE   VERSION
# docker-desktop   Ready    control-plane   ...   v1.3x.x
```

> `kubectl` viene incluido con Docker Desktop. Si no está en PATH: `winget install Kubernetes.kubectl`.

## 2. Instalar el Ingress Controller (ingress-nginx)

```powershell
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
kubectl wait --namespace ingress-nginx --for=condition=ready pod `
  --selector=app.kubernetes.io/component=controller --timeout=120s
```

En Docker Desktop, el Service `ingress-nginx-controller` es de tipo `LoadBalancer` y queda publicado en `localhost:80/443`:

```powershell
kubectl get svc -n ingress-nginx ingress-nginx-controller
# TYPE           EXTERNAL-IP   PORT(S)
# LoadBalancer   localhost     80:3xxxx/TCP,443:3xxxx/TCP
```

> Si el puerto 80 está ocupado en tu máquina (IIS, otro nginx…), libéralo o cambia el Service a NodePort y accede por `localhost:<nodeport>`.

## 3. Crear el namespace

`k8s/namespace.yaml` ya lo define; aplícalo:

```powershell
kubectl apply -f k8s/namespace.yaml
kubectl config set-context --current --namespace=l1-copilot   # opcional: namespace por defecto
```

## 4. Verificación final

```powershell
kubectl get ns l1-copilot
kubectl get pods -n ingress-nginx
curl.exe -s -o NUL -w "%{http_code}" http://localhost   # 404 de nginx = ingress vivo sin reglas: OK
```

✅ **Listo cuando:** `curl http://localhost` devuelve un 404 firmado por nginx (`404 Not Found` con server nginx) — significa que el Ingress Controller atiende y aún no hay reglas.

---

## Alternativa: kind (si no se quiere el K8s de Docker Desktop)

```powershell
winget install Kubernetes.kind
kind create cluster --name l1-copilot --config - @"
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
  - containerPort: 443
    hostPort: 443
"@
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
```

Diferencia importante con kind: las imágenes locales **no** son visibles automáticamente; tras cada `docker build` hay que cargarlas:

```powershell
kind load docker-image l1-copilot-backend:1.0.0 --name l1-copilot
kind load docker-image l1-copilot-frontend:1.0.0 --name l1-copilot
```

El resto de guías es idéntico para ambas opciones.

## Comandos kubectl de supervivencia (referencia rápida)

```powershell
kubectl get all -n l1-copilot                  # estado general
kubectl describe pod <pod> -n l1-copilot       # eventos: por qué no arranca
kubectl logs -f deploy/backend -n l1-copilot   # logs en vivo
kubectl exec -it <pod> -n l1-copilot -- sh     # shell dentro del contenedor
kubectl delete pod <pod> -n l1-copilot         # forzar recreación
kubectl rollout restart deploy/backend -n l1-copilot  # reinicio limpio
```
