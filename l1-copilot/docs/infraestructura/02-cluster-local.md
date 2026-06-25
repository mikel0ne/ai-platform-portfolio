# 02 — Clúster local (Rancher Desktop + k3s)

**Prerrequisitos:** Windows 11 con WSL2 habilitado, bash disponible (Ubuntu en WSL).
**Resultado:** k3s funcionando con Traefik, `kubectl` y `helm` operativos.

## 1. Instalar Rancher Desktop

Hay dos caminos. Eligen el mismo destino:

```bash
# Desde WSL — automatizado:
bash infra/scripts/01-install-rancher-desktop.sh
```

…o manualmente desde [rancherdesktop.io](https://rancherdesktop.io/) /
`winget install SUSE.RancherDesktop`.

Primer arranque → **Preferences**:

- **Container Engine:** `dockerd (moby)`. Razón: k3s usa entonces el mismo daemon que
  el CLI `docker`; sin embargo aquí se usa **registry local** en lugar de imágenes
  compartidas por daemon (el modelo es multi-servicio: ver guía 03).
- **Kubernetes:** habilitado, versión estable por defecto. **Traefik: habilitado**
  (viene marcado de serie; es el Ingress Controller del clúster).
- **Resources** (en WSL se ajusta vía `%USERPROFILE%\.wslconfig`): 4-6 GB de RAM.

Esperar a que la barra de estado muestre Kubernetes en verde.

Verificación:

```bash
kubectl config use-context rancher-desktop
kubectl get nodes
# NAME                   STATUS   ROLES                  AGE   VERSION
# <hostname>             Ready    control-plane,master   ...   v1.3x.x+k3s1

helm version --short      # incluido con Rancher Desktop
docker version            # CLI docker contra el dockerd de Rancher Desktop
```

> Si `kubectl`/`helm`/`docker` no están en el PATH, Rancher Desktop los deja en
> `%USERPROFILE%\.rd\bin` — añadir esa carpeta al PATH (Preferences → Application →
> PATH → Automatic).

## 2. Verificar Traefik

k3s despliega Traefik en `kube-system`; no hay nada que instalar:

```bash
kubectl get pods -n kube-system -l app.kubernetes.io/name=traefik
kubectl get svc  -n kube-system traefik
# TYPE           EXTERNAL-IP   PORT(S)
# LoadBalancer   <ip-local>    80:3xxxx/TCP,443:3xxxx/TCP
```

Rancher Desktop publica el LoadBalancer en `localhost:80/443`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost   # 404 = Traefik vivo sin reglas: OK
```

> Si el puerto 80 está ocupado en tu máquina (IIS, otro proxy…), libéralo: Traefik
> queda inaccesible en 80 y el Ingress no responderá.

## 3. Verificación final

```bash
kubectl get ns
helm ls -A          # vacío: las releases llegan en las guías 03-05
```

✅ **Listo cuando:** el nodo está `Ready` y `curl http://localhost` devuelve un 404 de
Traefik.

Los namespaces (`infra`, `registry`, `l1-copilot`) se crearán en las guías siguientes
(los scripts de infra los crean con `kubectl create namespace`; el de la app se aplica
con `kubectl apply -f infra/namespace-l1-copilot.yaml`).

## Comandos de supervivencia (referencia rápida)

```bash
kubectl get all -n l1-copilot                  # estado de la app
kubectl get all -n infra                       # estado del Postgres compartido
kubectl get all -n registry                    # estado del registry
helm ls -A                                     # releases instaladas
kubectl describe pod <pod> -n <ns>             # eventos: por qué no arranca
kubectl logs -f deploy/l1-copilot-backend -n l1-copilot
kubectl rollout restart deploy/l1-copilot-backend -n l1-copilot
wsl -d rancher-desktop                         # shell dentro de la VM/distro del clúster
```

## Nota: motor containerd en vez de moby

Si prefieres `containerd` como Container Engine, los builds se hacen con
`nerdctl --namespace k8s.io build ...` (el namespace `k8s.io` es el que ve el kubelet).
Todo lo demás de estas guías es idéntico.
