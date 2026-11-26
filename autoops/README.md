# AutoOps MCP

An **MCP (Model Context Protocol) server** that gives an AI assistant (Claude Code, GitHub Copilot or any MCP
client) **read-only access to a Kubernetes cluster** and an **advanced RAG over internal documentation**, so
it can debug running applications with real data instead of guessing.

AutoOps is not a chatbot: the MCP client does the reasoning. The server only exposes tools and data, safely.

## The problem

When an application fails in the cluster, a developer jumps between `kubectl describe`, events, logs, Grafana
and the internal wiki to work out what happened. AutoOps puts all of that within reach of the assistant they
already use: the assistant asks for a pod diagnosis, aggregated logs around a timestamp or the internal
standard that applies, and reasons about the cause with facts from the cluster.

## Architecture

```mermaid
flowchart LR
    client[MCP client<br/>Claude Code / Copilot] -- Streamable HTTP /mcp --> mcp
    ui[Search chat<br/>Angular] -- POST /search --> search

    subgraph app[autoops backend - Spring Boot]
        mcp[Spring AI MCP Server<br/>@Tool beans]
        search[SearchController<br/>+ AnswerComposer]
        k8s[K8sInspectionService]
        lokigw[LokiGateway]
        rag[RagService]
        crag[CRAG graph<br/>LangGraph4j]
        ingest[IngestionService<br/>/admin/*, token protected]
    end

    mcp --> k8s & lokigw & rag
    search --> rag
    rag --> crag
    crag --> weaviate[(Weaviate<br/>hybrid search + reranker)]
    crag --> gemini[[Gemini<br/>grade / rewrite / variants]]
    k8s -- read-only ServiceAccount --> api[[Kubernetes API]]
    lokigw --> loki[(Loki)]
    ingest --> github[[GitHub API]] & wikijs[Wiki.js GraphQL]
    ingest --> weaviate
```

### Corrective RAG (CRAG) loop

`search_docs` and `/search` share one retrieval core: a LangGraph4j subgraph that keeps searching until an LLM
grader confirms that the chunks cover every aspect of the question, or the iteration cap is reached.

```mermaid
flowchart LR
    start((start)) --> mq[multiQuery<br/>query variants + RRF]
    mq --> topK[topK<br/>best k after fusion] --> grade{grade<br/>LLM per chunk<br/>+ per question}
    grade -- covered or cap reached --> expand[expand<br/>section auto-merging] --> done((end))
    grade -- retry --> rewrite[rewrite<br/>query + wider k] --> retrieve --> topK
```

- **Hybrid search + native reranking** in a single Weaviate query (vector + BM25, cross-encoder rerank).
- **Multi-query fan-out** on the first pass only, fused with Reciprocal Rank Fusion; retries use one rewritten
  query to keep the LLM cost bounded.
- **LLM grading, never the raw score**: a high rerank score is not a reliable proxy for relevance. Coverage is
  judged per question, so a compound question is not "answered" by covering one half of it.
- **Section auto-merging** after grading: small sections, or sections hit several times, are returned whole;
  large ones return the hit with its neighbours, bounded to the section.
- **Four confidence levels** (`high | medium | low | none`) that the chat answer states explicitly.

## MCP tools

| Tool | Purpose |
|---|---|
| `search_docs` | Corrective RAG over the internal documentation; returns chunks, source, section, score and confidence. |
| `diagnose_pod` | One-call dossier of a pod: phase, conditions, not-ready reason, restarts, recent events and log tail. |
| `list_pods` / `list_workloads` | Status of pods and Deployments/StatefulSets/DaemonSets. |
| `describe_pod` / `list_events` / `pod_logs` | Building blocks for the client's own analysis. |
| `query_logs` | Aggregated history across pods and namespaces from Loki, narrowed by time window or timestamp. |
| `ping` | Connectivity check. |

Cluster access is **strictly read-only**: the ServiceAccount in [`infra/rbac-autoops.yaml`](../infra/rbac-autoops.yaml)
cannot write anything and cannot read Secrets.

## Stack

Java 17 · Spring Boot 4.1 · **Spring AI 2.0 MCP Server** (Streamable HTTP) · **LangChain4j 1.23** (Gemini chat
and embeddings) · **LangGraph4j 1.8** (CRAG subgraph) · Weaviate 1.27 + reranker-transformers · fabric8 Kubernetes
client · Loki · Angular 22 · Helm on k3s (Rancher Desktop).

## Running it

**Tests** (no cluster or API key needed; the LLM and the external APIs are replaced by test doubles):

```bash
cd autoops/backend
./mvnw -f ../../platform-commons/pom.xml install   # shared library, once and after changing it
./mvnw verify
```

**Backend on the host**, against the cluster services through port-forwards:

```bash
kubectl port-forward svc/weaviate 8088:8080 -n infra &
kubectl port-forward svc/loki 3100:3100 -n observability &
export GEMINI_API_KEY=...          # free key at https://aistudio.google.com/app/apikey
export AUTOOPS_ADMIN_TOKEN=...     # any long random string, protects /admin/*
cd autoops/backend && ./mvnw spring-boot:run
```

The Kubernetes tools use the current kubeconfig context. The search chat runs with
`cd autoops/frontend && npm ci && npm start` (http://localhost:4199).

**On k3s**, after the shared platform is in place (see [`infra/INFRA.md`](../infra/INFRA.md)):

```bash
./infra/scripts/deploy.sh --project autoops --component all
./infra/scripts/setup-app-ingress.sh --project autoops
bash infra/scripts/ingest-autoops.sh --reset      # indexes this repository's docs into Weaviate
```

`AUTOOPS_MCP_TOKEN` is a required deploy variable (`openssl rand -hex 32` into `infra/.env.local`): the deploy
aborts without it. Then point an MCP client at `http://autoops.local/mcp` sending the same value in the
`X-MCP-Token` header; for Claude Code the repo already ships [`.mcp.json`](../.mcp.json), which reads it from the
environment variable `AUTOOPS_MCP_TOKEN` (on Windows a user environment variable; restart the client after setting it).

## Evaluation

[`rag-golden-set.json`](backend/src/test/resources/rag-golden-set.json) holds 44 queries over the sample corpus:

- 21 baseline questions, each with the document and section that answer it;
- robustness cases: paraphrases, hidden sections, typos, other languages, multi-hop and ambiguous questions;
- 6 unanswerable queries (out of domain, not in the corpus, grader injection), each with a confidence cap;
- security cases: grader injection, prompt leak, exfiltration, destructive commands, malicious formats.

`RagGoldenSetTest` checks on every build that each expected source is exactly one Markdown file of the repo.
`RagEvalTest` measures, against a real index:

- **hybrid search + rerank**: hit@1, hit@5 and MRR of the expected section;
- **the full CRAG loop**: whether its evidence contains that section, plus confidence and iterations;
- **abstention and security**: the share of unanswerable and security queries handled as expected;
- results per category, with the failed cases listed.

It is opt-in because it needs an indexed Weaviate with the reranker and a Gemini key. It fails below
`RAG_EVAL_MIN_HIT`, `RAG_EVAL_MIN_ABSTENTION` or `RAG_EVAL_MIN_SECURITY` (each 0.8 by default). The 44-query set
has not been run against the model yet.

**Estimated cost** on the paid Gemini tier (free tier today): about $0.002-0.004 per search with Gemini 3.5
Flash-Lite and up to about $0.006 with a retry, roughly 25-30% less with 3.1; $2-6 per 1000 searches. Details in
the [root README](../README.md#estimated-cost).

To run the evaluation:

```bash
kubectl port-forward svc/weaviate 8088:8080 -n infra &
RAG_EVAL=true GEMINI_API_KEY=... AUTOOPS_WEAVIATE_CLASS_NAME=AutoopsDocChunk ./mvnw test -Dtest=RagEvalTest
```

## Design decisions

The ADRs are in [`docs/arquitectura/02-arquitectura-sistema.md`](docs/arquitectura/02-arquitectura-sistema.md) (Spanish):

| ADR | Decision |
|---|---|
| 001 | The product is an MCP server, not a copilot: the client reasons, the server provides data. |
| 002 | One application with two fronts (MCP tool and search chat) over a shared RAG core. |
| 003 | LLM and embedding provider behind an abstraction; Gemini free tier by default. |
| 004 | Spring AI for the MCP protocol, LangChain4j for the RAG. |
| 005 | Weaviate instead of pgvector, through the raw client to get hybrid search and native reranking. |
| 006 | Read-only access to the cluster, enforced by RBAC. |
| 007 | Corrective RAG as a LangGraph4j subgraph with the iteration cap on the edge. |
| 008 | Streamable HTTP transport inside the cluster. |
| 009 | Aggregated logs through Loki to complement the live pod log. |
| 010-011 | Multi-source ingestion (GitHub, Wiki.js) with hybrid chunking, and query-time section auto-merging instead of a stored neighbour window. |

## Security notes

- `/admin/*` (ingestion, vector store drop) requires the `X-Admin-Token` header and is not routed by the Ingress.
- Actuator exposes only `health` and `info`.
- `/mcp` requires the `X-MCP-Token` header whenever `AUTOOPS_MCP_TOKEN` is set; the cluster deploy makes that secret
  mandatory, while a local run without it leaves the endpoint open. `/search` has no authentication: this is a local
  demo. A real deployment would put OAuth (the MCP authorization spec) or an authenticating gateway in front of both.
- `/search` is capped by `SearchRateLimiter`, a global in-memory counter (not per client): it only holds with a
  single replica, and a scaled-out deployment would need a shared store or a gateway-level limit.

## Notes

- Internal design docs and infrastructure guides are in Spanish: [`docs/`](docs).
- The sample corpus (`corpus/`) and the wiki pages (`../wiki/content`) are **synthetic demo data**; the RAG also
  indexes this repository's own documentation.
