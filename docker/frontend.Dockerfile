# Multi-stage build shared by every Angular frontend in the repo. deploy.sh copies
# docker/frontend-nginx.conf to <context>/nginx.conf before building.
#   docker build -f docker/frontend.Dockerfile -t registry.local/<project>-frontend:<tag> ./autoops/frontend

FROM node:24-alpine AS build
WORKDIR /workspace

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# Unprivileged variant: runs as a non-root user on 8080, so the pod can set runAsNonRoot.
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY --from=build /workspace/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
