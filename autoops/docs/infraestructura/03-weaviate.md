# 03 — Weaviate (base vectorial del RAG)

AutoOps usa **Weaviate** como almacén de embeddings y motor de búsqueda del RAG. Se elige sobre pgvector porque
ofrece **búsqueda híbrida (BM25 + vector)** y **reranking** de fábrica, que es justo lo que necesita el "RAG
avanzado" (ver `docs/arquitectura/02` ADR-005). Los **embeddings los calcula AutoOps** (LangChain4j con Gemini),
así que Weaviate corre **sin módulo vectorizador** (`vectorizer: none`).

## 1. Weaviate es COMPARTIDO (namespace `infra`)

Igual que el Postgres del portfolio, hay **una sola instancia de Weaviate** para todos los proyectos, en el
namespace `infra`. Cada proyecto crea **su propia clase** (AutoOps → `AutoopsDocChunk`); no se comparte el
índice. El manifiesto y el script viven en la plataforma compartida, no en este proyecto:

- Manifiesto: `infra/components/weaviate/weaviate.yaml` (StatefulSet + Service + PVC, `ENABLE_MODULES=reranker-transformers`).
- Reranker: `infra/components/weaviate/reranker.yaml` (cross-encoder MiniLM).
- Script idempotente: `infra/scripts/06-setup-weaviate.sh` (despliega ambos y espera a Ready).

```bash
bash infra/scripts/06-setup-weaviate.sh
```

DNS interno: `weaviate.infra.svc.cluster.local` (HTTP `8080`, gRPC `50051`). Sin auth (local).

## 2. La clase `AutoopsDocChunk`

El esquema lo crea **AutoOps al primer uso** (`WeaviateGateway.ensureSchema`, idempotente), no se gestiona a
mano. Forma lógica:

```jsonc
{
  "class": "AutoopsDocChunk",
  "vectorizer": "none",
  "moduleConfig": { "reranker-transformers": {} },
  "properties": [
    { "name": "texto",   "dataType": ["text"] },
    { "name": "titulo",  "dataType": ["text"] },
    { "name": "fuente",  "dataType": ["text"], "tokenization": "field" },
    { "name": "seccion", "dataType": ["text"] },
    { "name": "url",     "dataType": ["text"], "tokenization": "field" },
    { "name": "origen",  "dataType": ["text"], "tokenization": "field" },
    { "name": "docId",   "dataType": ["text"], "tokenization": "field" },
    { "name": "hash",    "dataType": ["text"], "tokenization": "field" },
    { "name": "orden",   "dataType": ["int"] }
  ]
}
```

> **Esquema completo en doc de arquitectura `05-contratos-api-datos.md` §4** (contrato canónico). Importante:
> `ensureSchema()` es **idempotente** — si la clase ya existe en el clúster con un esquema más viejo (p. ej. sin
> `titulo`/`url`/`origen`/`docId`/`hash`), NO la migra sola. Tras cualquier cambio de esquema hace falta
> `bash infra/scripts/ingest-autoops.sh --reset` (borra y recrea la clase + reingesta) para que coincida con el
> código real.

La **dimensión del vector** la fija el primer embedding insertado (no está prefijada porque `vectorizer: none`).
El modelo es **Gemini `gemini-embedding-001`** (el free tier real; `text-embedding-004` **no** está disponible
en esta API/key). Si se cambia de modelo, recrear la clase y **re-ingestar** (`ingest-autoops.sh --reset`).

## 3. Reranking (activo)

El **reranker no es opcional aquí**: el código (`WeaviateGateway`) usa `rerank` de forma nativa en la query
(`_additional { rerank(...) }`), así que el módulo `reranker-transformers` **debe** estar habilitado (lo está
en el manifiesto de §1). Reordena por relevancia el resultado de la búsqueda híbrida (vector + BM25); el nodo
`grade` del bucle CRAG califica sobre ese score. Es la mayor ganancia de precisión del RAG.

## 4. Conexión desde AutoOps

El backend recibe la conexión por el ConfigMap del chart (`infra/values-autoops-backend.yaml`):

```
AUTOOPS_WEAVIATE_HOST=weaviate.infra.svc.cluster.local
AUTOOPS_WEAVIATE_PORT=8080
AUTOOPS_WEAVIATE_GRPC_PORT=50051
AUTOOPS_WEAVIATE_CLASS_NAME=AutoopsDocChunk
```

## 5. Acceso local (debug) y reset

```bash
# Inspeccionar esquema/objetos desde el host
kubectl port-forward svc/weaviate 8088:8080 -n infra
# http://localhost:8088/v1/schema   ·   http://localhost:8088/v1/objects?class=AutoopsDocChunk

# Reset del índice de AutoOps (re-ingesta con embeddings nuevos)
bash infra/scripts/ingest-autoops.sh --reset
```

La **ingesta del corpus** (poblar `AutoopsDocChunk`) se ejecuta tras desplegar la app: ver guía
[07-operacion-troubleshooting.md](07-operacion-troubleshooting.md) §3.

Continuar con [04-dockerizacion.md](04-dockerizacion.md).
