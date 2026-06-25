# Frontend — Chat de búsqueda de AutoOps

SPA Angular (front *secundario* del proyecto). Es una **única vista**: un chat que consulta la
documentación interna vía el endpoint RAG del backend y muestra la respuesta **con sus citas y el nivel
de confianza**. Sin login, historial ni K8s — el front principal es el servidor MCP (ver
`docs/arquitectura/02-arquitectura-sistema.md`).

## Contrato

`POST /search` → `{ consulta }` ⇒ `{ respuesta, citas: [{ fuente, seccion, score }], confianza }`
(ver `docs/arquitectura/05-contratos-api-datos.md` §2-3).

## Desarrollo

```bash
npm install
npm start            # ng serve en http://localhost:4199
```

`proxy.conf.json` redirige `/search` y `/actuator` al backend en `http://localhost:8080`, así que arranca
el backend (`scripts/start.sh`) para que las consultas respondan. El estado vacío y los ejemplos funcionan
sin backend.

## Build y tests

```bash
npm run build                                  # bundle de producción en dist/frontend
npm test -- --watch=false --browsers=ChromeHeadless
```

En despliegue, nginx sirve los estáticos y el Ingress enruta `/search` (y el SSE de MCP) al backend
(`docker/frontend-nginx.conf`).
