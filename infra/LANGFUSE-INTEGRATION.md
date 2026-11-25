# Langfuse integration

How the backends (`autoops/backend`, `l1-copilot/backend`) report LLM traces and scores to the
self-hosted Langfuse of the cluster (`infra/INFRA.md`, section 8). Disabled by default: with
`<project>.langfuse.enabled=false` the injected `Tracer` is a no-op and nothing is exported.

## 1. Approach

Plain **OpenTelemetry Java SDK** (no Spring starter, no Micrometer), OTLP/HTTP protobuf exporter,
and spans created by hand at three hooks:

| Hook | Produces |
|------|----------|
| LangChain4j `ChatModelListener` (set with `.listeners(...)` on the model builder) | one `generation` per real model call |
| LangGraph4j `NodeHook.WrapCall` (registered before compiling the graph) | one span per graph node |
| Entry-point code (`LangfuseTracing.root`) | the root span, i.e. the trace |

Scores have no OTLP mapping, so a small gateway (`LangfuseScoreGateway`) posts them to Langfuse.

Why not the alternatives: Micrometer/Spring Boot OTel starters only instrument Spring AI `ChatModel`
(neither backend calls the LLM through it), differ between Spring Boot 3.5 and 4.x, and clash with this
setup; the Java agent gives HTTP spans but no prompts, tokens or verdicts. **Do not add
`micrometer-tracing-bridge-otel`, `opentelemetry-spring-boot-starter` or `spring-boot-starter-opentelemetry`.**

## 2. OTLP endpoint and attributes

| What | Value |
|------|-------|
| Traces URL | `<host>/api/public/otel/v1/traces` (the exporter does not append `/v1/traces`; the code does) |
| Protocol | OTLP over HTTP (protobuf). gRPC is **not** supported |
| Auth | `Authorization: Basic base64(<public key>:<secret key>)` of the Langfuse project |
| Extra header | `x-langfuse-ingestion-version: 4`; without it data can take up to 10 min to show up |

Attributes Langfuse maps (anything else lands in a non-filterable `metadata.attributes`, so filterable
data goes under `langfuse.trace.metadata.*` / `langfuse.observation.metadata.*`):

- Trace: `langfuse.trace.name`, `langfuse.session.id`, `langfuse.user.id`, `langfuse.trace.tags`,
  `langfuse.environment`, `langfuse.release`.
- Observation: `langfuse.observation.type` (`span|generation|chain|tool|retriever|agent|...`),
  `.level`, `.status_message`, `.input`, `.output`, `.model.name`, `.model.parameters` (JSON string),
  `.usage_details` (JSON string), `.cost_details`.
- In v4 the trace input/output is the root span's.

## 3. Dependencies

`io.opentelemetry:opentelemetry-bom:1.68.0` imported in `dependencyManagement`, plus
`opentelemetry-sdk`, `opentelemetry-exporter-otlp` and (test) `opentelemetry-sdk-testing`. No
`opentelemetry-sdk-extension-autoconfigure` (the Basic header is built from the two keys) and no
`semconv` constants (the `gen_ai.*` ones are in the alpha `-incubating` artifact).

## 4. Scores and Langfuse 4.83.0 in `events_only` mode

This Langfuse runs in `events_only` write mode. Verified against the cluster:

- `POST /api/public/scores` (and the v1 read endpoints `/traces`, `/observations`, `/metrics`) answer **404**.
- Scores are written with **`POST /api/public/ingestion`**, a batch of `score-create` events:
  `{"batch":[{"id":<uuid>,"type":"score-create","timestamp":<ISO, millis>,"body":{...}}]}`.
- The event `body` **must carry an `id`** (a UUID per score). Without it Langfuse answers `207` as if
  everything went fine and silently drops the score. Always inspect the `errors` list of the 207 body.
- `BOOLEAN` scores take `1`/`0` (not a JSON boolean) with an explicit `dataType`.
- Reads are `GET /api/public/v3/scores` and `GET /api/public/v2/observations`.
- The JDK `HttpClient` sends `Upgrade: h2c` on plain HTTP and Langfuse answers by closing the
  connection. Force **HTTP/1.1** on every client that talks to Langfuse's REST API.

Telemetry is best-effort: errors are logged at `warn` and never propagate to the request.

## 5. Configuration

Same block in both projects, with prefix `autoops.langfuse` / `copilot.langfuse`:

```yaml
langfuse:
  enabled: false                                   # only the k8s ConfigMap turns it on
  endpoint: http://langfuse.local/api/public/otel  # WITHOUT /v1/traces
  public-key: ${LANGFUSE_PUBLIC_KEY:}
  secret-key: ${LANGFUSE_SECRET_KEY:}
  environment: local
  release: "@project.version@"
  sample-ratio: 1.0
  capture-content: true                            # false: neither prompts nor answers leave the pod
```

Deployment wiring (`deploy.sh` creates one Secret key per variable, named like the variable, from
`infra/.env.local`; the chart maps it to the pod variable):

| | AutoOps | L1 Copilot |
|---|---------|-----------|
| `.env.local` variables | `LANGFUSE_AUTOOPS_PUBLIC_KEY` / `_SECRET_KEY` | `LANGFUSE_L1COPILOT_PUBLIC_KEY` / `_SECRET_KEY` |
| K8s Secret | `langfuse-api-keys` | `langfuse-api-keys` |
| Pod variables | `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | same |
| ConfigMap | `AUTOOPS_LANGFUSE_ENABLED/ENDPOINT/ENVIRONMENT` | `COPILOT_LANGFUSE_*` |

In-cluster endpoint: `http://langfuse-web.langfuse.svc.cluster.local:3000/api/public/otel`.

## 6. AutoOps

A trace is one RAG query. Two entry points share `RagService.search`:

| Entry | Trace name | Root type |
|-------|-----------|-----------|
| MCP tool `search_docs` (`DocsTools`) | `search_docs` | `tool` |
| `POST /search` (`SearchController`) | `search` | `chain` |

```
search_docs | search                       root
├─ crag                                   chain       around cragGraph.invoke
│  ├─ <node> (one per graph node)         chain/span  NodeHook.WrapCall in CragGraph
│  │  ├─ gemini.chat                      generation  ChatModelListener
│  │  └─ weaviate.retrieve                retriever   WeaviateGateway
│  └─ expand                              weaviate.fullSection / weaviate.neighboursInSection
└─ compose (only /search)                 chain       AnswerComposer
   └─ gemini.chat                         generation
```

Root metadata: `confidence`, `iterations`, `k`, `chunks`, `coverage`, `front`. Scores:
`rag_confidence` (`CATEGORICAL`) and `crag_iterations` (`NUMERIC`). The node hook assumes nodes run on
the calling thread (true for `node_async`); truly async nodes would need explicit context propagation.
The hook is registered in `CragGraph.build()`, not in the constructor, to keep the model lazy.

## 7. Verification without `@SpringBootTest`

Tests are plain unit tests with an in-memory exporter (`SimpleSpanProcessor` + `InMemorySpanExporter`)
and no network: attribute names, observation types, `node -> generation` nesting, error paths, and the
exact JSON of the score batch (including the per-score `id`). For a transport smoke test, port-forward
`svc/langfuse-web` and `POST` a minimal JSON span to `/api/public/otel/v1/traces` with the Basic header:
a 4xx means a wrong key pair or path; a 2xx with nothing in the UI points to the ingestion-version header.
In short-lived runs call `forceFlush()` before exiting: the batch processor drops pending spans otherwise.

## 8. Risks

1. Prompts carry document fragments (AutoOps) or user messages and order data (L1): with
   `capture-content: true` they leave the pod. The switch exists; leaving it on is a conscious choice.
2. A slow or down endpoint can fill the batch queue and drop spans silently (10 s timeout, best-effort).
3. Not verified: whether Spring AI exposes an MCP session id for `langfuse.session.id`, and whether
   `GoogleAiEmbeddingModel` accepts listeners (query embeddings are not traced).
