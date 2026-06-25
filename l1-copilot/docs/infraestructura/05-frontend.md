# 05 — Frontend (Angular + nginx)

**Prerrequisitos:** guía 04 (backend desplegado), frontend implementado en
`l1-copilot/frontend/` (Fase 5 del plan de arquitectura).
**Resultado:** SPA servida por nginx en el clúster como `l1-copilot-frontend:80`,
desplegada con el chart `generic-app`.

## 1. Imagen

[`docker/frontend.Dockerfile`](../../docker/frontend.Dockerfile) — multi-stage:

1. **Stage build** (`node:20-alpine`): `npm ci && npm run build` (salida en
   `dist/frontend/browser` con Angular 17+).
2. **Stage runtime** (`nginx:1.27-alpine`): copia los estáticos y
   [`docker/frontend-nginx.conf`](../../docker/frontend-nginx.conf).

El `frontend-nginx.conf` hace dos cosas y solo dos:

- **SPA fallback**: `try_files $uri $uri/ /index.html;` (rutas de Angular como `/chat`
  devuelven `index.html`).
- **Cache**: estáticos con hash cacheados 1 año, `index.html` sin caché.

> **No proxya `/api`**: en el clúster el enrutado `/api → backend` lo hace el Ingress
> de Traefik (guía 06). Así nginx no necesita conocer el backend. El proxy `/api` solo
> existe en desarrollo con `ng serve`.

Build y push:

```bash
docker build -t registry.local/l1-copilot-frontend:1.0.0 -f docker/frontend.Dockerfile l1-copilot/frontend
docker push registry.local/l1-copilot-frontend:1.0.0
```

> El contexto de build es `l1-copilot/frontend` (declarado como `CONTEXT_FRONTEND` en
> `infra/projects/l1-copilot.env`). En la práctica lo hace `deploy.sh`, que además copia
> `docker/frontend-nginx.conf` al contexto antes de construir.

## 2. Desplegar

Forma real (recomendada):

```bash
infra/scripts/deploy.sh --project l1-copilot --component frontend --tag <tag-único>
kubectl rollout status deploy/l1-copilot-frontend -n l1-copilot
```

Lo que hace por dentro con el chart `generic-app`:

```bash
helm upgrade --install l1-copilot-frontend infra/charts/generic-app \
  -f infra/values-l1-copilot-frontend.yaml --set image.tag=<tag-único>
```

Puntos clave del values
([`infra/values-l1-copilot-frontend.yaml`](../../infra/values-l1-copilot-frontend.yaml)):

- Deployment de 1 réplica (estáticos puros; podría escalar sin problema, a diferencia
  del backend).
- Probes HTTP a `/` (nginx responde el index).
- Recursos mínimos: requests 16Mi/10m, limits 64Mi/100m.
- Service ClusterIP `l1-copilot-frontend:80`.
- **`ingress.enabled: false`** — el Ingress lo aplica el script genérico
  `infra/scripts/setup-app-ingress.sh --project l1-copilot` con `kubectl`. Ver guía 06.
- `runAsNonRoot: false` porque la imagen `nginx:alpine` corre como root; si quieres
  estricto, construir sobre `nginxinc/nginx-unprivileged` (puerto 8080) y ajustar
  `container.port`.

## 3. Verificación

```bash
kubectl get pods -n l1-copilot -l app.kubernetes.io/instance=l1-copilot-frontend

kubectl run tmp --rm -it --image=curlimages/curl -n l1-copilot --restart=Never -- \
  curl -s -o /dev/null -w "%{http_code}" http://l1-copilot-frontend:80/
# 200
```

✅ **Listo cuando:** el curl interno devuelve 200 y el HTML contiene el `<app-root>` de
Angular.

## Troubleshooting

| Síntoma | Causa | Arreglo |
|---|---|---|
| 404 al refrescar `/chat` en el navegador | falta el SPA fallback | revisar que `frontend-nginx.conf` se copió a `/etc/nginx/conf.d/default.conf` |
| Pantalla en blanco, 404 de JS en consola | `npm run build` con `baseHref` mal o copia de la carpeta equivocada (`dist/frontend` vs `dist/frontend/browser`) | inspeccionar la imagen: `docker run --rm -it localhost:30500/l1-copilot-frontend:1.0.0 ls /usr/share/nginx/html` |
| Llamadas a `/api` devuelven 404 | se accede al Service `l1-copilot-frontend` directamente sin pasar por el Ingress | usar la URL del Ingress (guía 06); el frontend solo sabe servir estáticos |
