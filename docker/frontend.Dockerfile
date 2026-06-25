# Frontend Angular servido por nginx — build multi-stage. Genérico para cualquier
# proyecto del monorepo (l1-copilot, autoops…): lo invoca infra/scripts/deploy.sh
# con la imagen y el contexto del proyecto.
# Uso (el contexto es la carpeta frontend/; el conf se copia desde docker/):
#   docker build -f docker/frontend.Dockerfile -t registry.local/<proyecto>-frontend:<tag> ./frontend
# NOTA: deploy.sh copia docker/frontend-nginx.conf a <contexto>/nginx.conf antes del build.

# ---- Stage 1: build ----
FROM node:20-alpine AS build
WORKDIR /workspace

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Stage 2: runtime ----
FROM nginx:1.27-alpine

# Angular 17+ deja la app en dist/<nombre>/browser
COPY --from=build /workspace/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
