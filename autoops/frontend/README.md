# AutoOps search chat (frontend)

Angular SPA, the *secondary* front of AutoOps (the primary one is the MCP server). A single view: a chat
that queries the internal documentation through the backend RAG and shows the answer **with its citations
and confidence level**. No login, history or Kubernetes access.

## Contract

`POST /search` with `{ query }` returns `{ answer, citations: [{ source, title, section, url, origin, score }], confidence }`,
where `confidence` is `high | medium | low | none`.

## Development

```bash
npm ci
npm start            # ng serve on http://localhost:4199
```

`proxy.conf.json` forwards `/search` and `/actuator` to the backend on `http://localhost:8080`. The empty state
and the sample questions work without a backend.

## Build and tests

```bash
npm run build
npx ng test --watch=false --browsers=ChromeHeadless
```

Once deployed, nginx serves the static files and the Ingress routes `/search` and `/mcp` to the backend
(`docker/frontend-nginx.conf`).
