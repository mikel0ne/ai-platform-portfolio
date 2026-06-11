# 05 — Frontend (Angular + nginx)

**Prerrequisitos:** guía 04 (backend desplegado), frontend implementado en `frontend/` (Fase 5 del plan de arquitectura).
**Resultado:** SPA servida por nginx en el clúster como `frontend:80`.

## 1. Imagen Docker

[`docker/frontend.Dockerfile`](../../docker/frontend.Dockerfile) — multi-stage:

1. **Stage build** (`node:20-alpine`): `npm ci && npm run build` (salida en `dist/frontend/browser` con Angular 17+).
2. **Stage runtime** (`nginx:1.27-alpine`): copia los estáticos y [`docker/frontend-nginx.conf`](../../docker/frontend-nginx.conf).

El `frontend-nginx.conf` hace dos cosas y solo dos:

- **SPA fallback**: `try_files $uri $uri/ /index.html;` (las rutas de Angular como `/chat` deben devolver `index.html`).
- **Cache**: estáticos con hash cacheados 1 año, `index.html` sin caché.

> **No proxya `/api`**: en el clúster, el enrutado `/api → backend` lo hace el **Ingress** (guía 06). Así nginx no necesita conocer el backend y la imagen vale para cualquier entorno. (El proxy `/api` solo existe en desarrollo con `ng serve`.)

Build:

```powershell
docker build -f docker/frontend.Dockerfile -t l1-copilot-frontend:1.0.0 ./frontend
```

(Con kind: `kind load docker-image l1-copilot-frontend:1.0.0 --name l1-copilot`.)

## 2. Desplegar

```powershell
kubectl apply -f k8s/frontend.yaml
kubectl rollout status deploy/frontend -n l1-copilot
```

Puntos del manifiesto ([`k8s/frontend.yaml`](../../k8s/frontend.yaml)):

- Deployment de 1 réplica (estáticos puros; podría escalar sin problema, a diferencia del backend).
- Probes HTTP a `/` (nginx responde el index).
- Recursos mínimos: requests 16Mi/10m, limits 64Mi/100m.
- Service ClusterIP `frontend:80`.
- Contenedor nginx **no-root** sobre puerto 8080 interno (imagen `nginxinc/nginx-unprivileged` como alternativa si se quiere `runAsNonRoot: true` estricto; anotado en el manifiesto).

## 3. Verificación

```powershell
kubectl get pods -n l1-copilot -l app=frontend

kubectl run tmp --rm -it --image=curlimages/curl -n l1-copilot --restart=Never -- `
  curl -s -o /dev/null -w "%{http_code}" http://frontend:80/
# 200
```

✅ **Listo cuando:** el curl interno devuelve 200 y el HTML contiene el `<app-root>` de Angular (`curl -s http://frontend | findstr app-root` desde el pod temporal).

## Troubleshooting

| Síntoma | Causa | Arreglo |
|---|---|---|
| 404 al refrescar `/chat` en el navegador | falta el SPA fallback | revisar que `frontend-nginx.conf` se copió a `/etc/nginx/conf.d/default.conf` |
| Pantalla en blanco, 404 de JS en consola | `npm run build` con `baseHref` mal o copia de la carpeta equivocada (`dist/frontend` vs `dist/frontend/browser`) | inspeccionar la imagen: `docker run --rm -it l1-copilot-frontend:1.0.0 ls /usr/share/nginx/html` |
| Llamadas a `/api` devuelven 404 | se accede al Service `frontend` directamente sin pasar por el Ingress | usar la URL del Ingress (guía 06); el frontend solo sabe servir estáticos |
