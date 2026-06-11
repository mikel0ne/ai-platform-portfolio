# Frontend Angular servido por nginx — build multi-stage.
# Uso (el contexto es la carpeta frontend/; el conf se copia desde docker/):
#   docker build -f docker/frontend.Dockerfile -t l1-copilot-frontend:1.0.0 ./frontend
# NOTA: requiere que docker/frontend-nginx.conf se haya copiado a frontend/nginx.conf
#   (o ajustar el COPY de abajo si se prefiere otro layout).
# Ver docs/infraestructura/05-frontend.md.

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
