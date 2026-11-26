# AI Platform Portfolio

I am [Miguel](https://www.linkedin.com/in/maciordia), a Java Tech Lead with 11 years of Java and Spring, plus Kubernetes and DevSecOps. This repository
holds three generative AI projects I built as personal proofs of concept, all in **Java 17 / Spring Boot**, on
**LangChain4j**, **LangGraph4j** and **Spring AI**, deployed to a local **k3s** cluster. Each one tackles a
problem that matters once an LLM touches real systems: answers you can trust, actions the model cannot
trigger on its own, and tool access that is safe by construction.

| | Project | In one line |
|---|---|---|
| 1 | [Corrective RAG](#1-corrective-rag) | Retrieval that checks its own evidence before answering, and says how confident it is. |
| 2 | [L1 Support Copilot](#2-l1-support-copilot) | A support agent where the LLM classifies and the code decides. |
| 3 | [AutoOps MCP server](#3-autoops-mcp-server) | Read-only Kubernetes, logs and docs for any MCP client (Claude Code, Copilot). |

## 1. Corrective RAG

Part of [AutoOps](autoops/README.md), shared by its `search_docs` MCP tool and its search chat. A plain
top-k search returns chunks that look relevant and answers anyway; this one judges what it retrieved.

- **Hybrid search with reranking in one Weaviate query**: BM25 + vector, then a cross-encoder rerank.
- **Multi-query on the first pass**: the question is expanded into variants and fused with Reciprocal Rank
  Fusion; retries use a single rewritten query and a wider `k` to keep LLM cost bounded.
- **CRAG loop**: an LLM grades each chunk and the coverage of each sub-question, so a compound question is not
  "answered" by covering half of it. The rerank score is never used as a proxy for relevance. The iteration
  cap sits on the graph edge, so the loop terminates whatever the grader says.
- **Query-time section auto-merging**: small or repeatedly hit sections are returned whole, mid-sized ones as
  the hit plus its neighbours within the section, under a size ceiling (ADR-011, which supersedes the stored
  neighbour window of ADR-010).
- **Explicit confidence** (`high | medium | low | none`) and citations in every answer; ingestion from local
  Markdown, GitHub repositories and Wiki.js.

```mermaid
flowchart LR
    start((question)) --> mq[multiQuery<br/>variants + RRF]
    mq --> topK[topK<br/>best k after fusion] --> grade{grade<br/>LLM per chunk<br/>+ per sub-question}
    grade -- covered or cap reached --> expand[expand<br/>section auto-merging] --> done((evidence +<br/>confidence))
    grade -- retry --> rewrite[rewrite<br/>query + wider k] --> retrieve --> topK
```

![The AutoOps search chat answering a question about probes with a high-confidence badge and cited sources, then the Langfuse trace of that search](docs/img/autoops-search-demo.gif)

The answer cites the exact sections it used and declares its confidence. Behind it, every search is a
Langfuse trace with the CRAG nodes as spans (multi-query, three hybrid retrievals, grade, section
expansion) and the confidence and iteration count as scores:

![Langfuse trace of one AutoOps search: crag, multiQuery, weaviate.retrieve, topK, grade and expand spans](docs/img/langfuse-autoops-trace.png)

**What to look at**

- [`CragGraph.java`](autoops/backend/src/main/java/io/github/mikel0ne/autoops/rag/crag/CragGraph.java): the
  LangGraph4j subgraph and the iteration cap on the edge.
- [`GradeNode.java`](autoops/backend/src/main/java/io/github/mikel0ne/autoops/rag/crag/nodes/GradeNode.java):
  per-chunk verdicts and per-sub-question coverage.
- [`ExpandNode.java`](autoops/backend/src/main/java/io/github/mikel0ne/autoops/rag/crag/nodes/ExpandNode.java):
  section auto-merging.
- [`RagEvalTest.java`](autoops/backend/src/test/java/io/github/mikel0ne/autoops/rag/eval/RagEvalTest.java) and
  the ADRs 005, 007, 010 and 011 in
  [`02-arquitectura-sistema.md`](autoops/docs/arquitectura/02-arquitectura-sistema.md) (Spanish).

## 2. L1 Support Copilot

[A level-1 support agent](l1-copilot/README.md) for an e-commerce site: it tracks, cancels and returns orders
and opens tickets, with real effects on the database. Letting the LLM decide whether an order can be
cancelled is the failure mode it is designed against: **the LLM classifies, the code decides**.

- **LangGraph4j state graph** with a checkpointer, so clarifications, digressions and pending confirmations
  survive between HTTP turns.
- The LLM only returns an intent and slots; routing is pure Java functions over the state, and business rules
  live in domain services, not prompts.
- **A verifier with an asymmetric threshold**: on doubt, a read such as tracking goes ahead, while cancel,
  return or ticket creation stops and asks.
- **Confirmations are only valid while pending for that very order**: a forged or stale confirm restarts the
  request. The `userId` comes from the session, never from the user's text. The session is a signed *demo*
  token (HMAC-SHA256, issued by a simulated login); real authentication is out of scope.
- **Security tests that assume the LLM is already fooled**: the mocked classifier and verifier say yes to
  jailbreaks, IDOR attempts and injected confirmations, and the tests check the code still refuses.
- **Retries around every model call** (`copilot.llm-retry`): overloads (503) and timeouts back off, a per-minute
  rate limit waits for the window the provider reports, and a daily quota is never retried.
- **A global cap on AutoOps `/search`** (`SearchRateLimiter`): it is an in-memory counter, so it only holds with
  a single replica.

```mermaid
flowchart LR
    start((turn)) --> router{router<br/>LLM intent + slots}
    router -- fresh action --> verify{verify<br/>SURE / DOUBT / REJECT}
    verify -- doubt on an action, or reject --> clarify
    verify -- proceed --> resolve[resolve_order]
    router --> resolve
    resolve --> tracking
    resolve --> cancel_check --> cancel_exec
    resolve --> return_check --> return_exec
    resolve --> support[support<br/>ticket gate]
    router --> tickets & faq & chatty & fallback
    tracking & cancel_exec & return_exec & support & tickets & faq & chatty & fallback & clarify --> respond[respond<br/>LLM wording] --> stop((reply))
```

![L1 Support Copilot: listing orders, asking for a cancellation that needs a confirmation card, and refusing a jailbreak that targets another user's order](docs/img/l1-copilot-demo.gif)

Three turns, left to right in the GIF: the assistant lists the user's orders as cards; a cancellation
is never executed on the first message, it asks for a confirmation that cannot be forged; and a
jailbreak that claims to be an administrator and names an order of another user is refused, because
the order lookup is filtered by the session's user, not by what the text says.

Every turn is also a Langfuse trace, with the router, verifier, business-rule check and responder as
spans and the cost of each LLM call:

![Langfuse trace of one L1 turn: chat.turn with router, verify, cancel_check and respond spans and the cost of each Gemini call](docs/img/langfuse-l1-trace.png)

**What to look at**

- [`SupportRouting.java`](l1-copilot/backend/src/main/java/io/github/mikel0ne/l1copilot/agent/SupportRouting.java):
  every routing decision as a pure function, including the verifier's asymmetric rule.
- [`RouterShortcuts.java`](l1-copilot/backend/src/main/java/io/github/mikel0ne/l1copilot/agent/node/RouterShortcuts.java):
  confirm commands accepted only for the confirmation pending on that order. `RouterNode` orchestrates it with
  `RouterNavigation` and the ordered steps of `RouterPolicies`.
- [`SecurityInjectionTest.java`](l1-copilot/backend/src/test/java/io/github/mikel0ne/l1copilot/agent/SecurityInjectionTest.java):
  jailbreak, IDOR, confirmation injection, forged confirm and conversation hijack.
- ADRs 3 and 4 in [`02-arquitectura-sistema.md`](l1-copilot/docs/arquitectura/02-arquitectura-sistema.md) (Spanish).

## 3. AutoOps MCP server

[AutoOps](autoops/README.md) is an MCP server, not a chatbot: the assistant a developer already uses does the
reasoning, and the server gives it facts from the cluster instead of guesses. Built on the **Spring AI MCP
Server** over **Streamable HTTP**, so it works with any MCP client (Claude Code, GitHub Copilot); the repo
ships a [`.mcp.json`](.mcp.json) for Claude Code.

- **Live pod state**: `diagnose_pod` returns a one-call dossier (phase, conditions, restarts, events, log
  tail), plus `list_pods`, `list_workloads`, `describe_pod`, `list_events` and `pod_logs`.
- **History from Loki**: `query_logs` across pods and namespaces, by time window or around a timestamp.
- **Docs search**: `search_docs`, the corrective RAG above.
- **Read-only by RBAC, not by convention**: the ServiceAccount can get and list pods, logs, events and
  workloads, and nothing else; Secrets and ConfigMaps are deliberately left out.

```mermaid
flowchart LR
    client[MCP client<br/>Claude Code / Copilot] -- Streamable HTTP /mcp --> mcp
    subgraph app[autoops backend - Spring Boot]
        mcp[Spring AI MCP Server<br/>@Tool beans]
        k8s[K8sInspectionService]
        lokigw[LokiGateway]
        rag[RagService<br/>CRAG]
    end
    mcp --> k8s & lokigw & rag
    k8s -- read-only ServiceAccount --> api[[Kubernetes API]]
    lokigw --> loki[(Loki)]
    rag --> weaviate[(Weaviate<br/>hybrid + reranker)]
    rag --> gemini[[Gemini]]
```

![A deployment whose image does not exist: list_pods and diagnose_pod return ImagePullBackOff and "not found", and the assistant concludes the image must be published](docs/img/autoops-diagnose-demo.gif)

A deliberately broken Deployment ([`demo-broken.yaml`](scripts/readme-media/demo-broken.yaml), image tag that
does not exist). The two tool calls and their results are real, taken from the running server and
abridged with `…`; the window and the closing paragraph are a re-creation, not a recording of Claude Code.

And the read-only guarantee, checked against the cluster (the last two must answer `no`):

```console
$ kubectl auth can-i list pods    --as=system:serviceaccount:autoops:autoops-mcp -A
yes
$ kubectl auth can-i get pods/log --as=system:serviceaccount:autoops:autoops-mcp -A
yes
$ kubectl auth can-i get secrets  --as=system:serviceaccount:autoops:autoops-mcp -A
no
$ kubectl auth can-i delete pods  --as=system:serviceaccount:autoops:autoops-mcp -A
no
```

**What to look at**

- [`rbac-autoops.yaml`](infra/rbac-autoops.yaml): the read-only ClusterRole, with the `kubectl auth can-i`
  checks that must answer no.
- [`KubernetesTools.java`](autoops/backend/src/main/java/io/github/mikel0ne/autoops/tools/KubernetesTools.java)
  and [`ObservabilityTools.java`](autoops/backend/src/main/java/io/github/mikel0ne/autoops/tools/ObservabilityTools.java):
  the MCP tool definitions.
- ADRs 001, 006 and 008 in [`02-arquitectura-sistema.md`](autoops/docs/arquitectura/02-arquitectura-sistema.md) (Spanish).

## Platform

Both applications share one platform on k3s (Rancher Desktop): a **single generic Helm chart**
([`infra/charts/generic-app`](infra/charts/generic-app)) deploys every backend, frontend and Wiki.js from a
values file each, next to a shared PostgreSQL, Weaviate with the reranker, and **Loki + Grafana** for logs.
**CI** ([`ci.yml`](.github/workflows/ci.yml)) builds and tests both backends and both Angular frontends, lints
and renders the Helm chart against every values file, and runs ShellCheck on the scripts. AutoOps can diagnose
the L1 Copilot pods and its RAG indexes this repository's docs, so the projects double as each other's test bed.

```mermaid
flowchart LR
    subgraph clients[Clients]
        cc[Claude Code / Copilot]
        browser[Browser]
    end
    subgraph k3s[k3s cluster - Rancher Desktop]
        traefik[Traefik Ingress]
        subgraph autoops[ns autoops]
            amcp[autoops backend<br/>MCP + /search]
            afe[autoops frontend]
        end
        subgraph l1[ns l1-copilot]
            lbe[l1-copilot backend<br/>/api]
            lfe[l1-copilot frontend]
        end
        subgraph infra[ns infra]
            pg[(PostgreSQL)]
            wv[(Weaviate + reranker)]
        end
        subgraph obs[ns observability]
            loki[(Loki)] --- graf[Grafana]
        end
        wiki[Wiki.js<br/>ns wiki]
    end
    gemini[[Gemini API]]
    cc -- MCP Streamable HTTP --> traefik
    browser --> traefik
    traefik --> amcp & afe & lbe & lfe
    amcp --> wv
    amcp -- read-only --> k8sapi[[Kubernetes API]]
    amcp --> loki
    amcp --> wiki
    lbe --> pg
    wiki --> pg
    amcp & lbe --> gemini
```

## Evaluation

Two golden sets run on demand against the real model. They need an API key (and the RAG one an indexed
Weaviate), so they are opt-in. CI validates both sets offline on every build. Results are reported per category
and the failed cases are listed.

- **RAG**: [`rag-golden-set.json`](autoops/backend/src/test/resources/rag-golden-set.json), 44 queries: 21
  baseline questions with the document and section that answer each; robustness cases (paraphrases, hidden
  sections, typos, other languages, multi-hop, ambiguity); 6 unanswerable queries whose CRAG confidence must
  stay under a cap; and security cases (grader injection, prompt leak, exfiltration, destructive commands,
  malicious formats). `RagEvalTest` reports hit@1, hit@5 and MRR for hybrid search + rerank and for the CRAG
  evidence. It fails below 0.8 on hit@5, on abstention and on security.
- **Router**: [`golden-router-cases.json`](l1-copilot/backend/src/test/resources/golden-router-cases.json), 102
  messages in 24 categories: 54 security cases (history, tool-data, encoded and multilingual injection,
  jailbreaks, roleplay, privilege claims) and 60 held-out cases that were never used to tune the prompt. Some
  cases carry conversation history, agent state or an expected confirmation. `RouterEvalTest` reports the raw
  model output and the guarded result after the deterministic assent guard. It fails below 0.7 overall or 0.8
  on security. A test also fails if the router prompt quotes a golden case. Decisions are cached per model in
  `target/`, and an API error (429, 503, timeout) leaves a case open instead of counting it as a failure, so a
  rerun completes the missing cases.
- **Router failures end to end**: `RouterFailureE2eEvalTest` replays every case the router got wrong through
  the real graph, using the real verifier and tools that accept any order (the worst case). Each failure is
  graded by its consequence: GRAVE (an action the user did not ask for runs or is offered), MITIGADO (the flow
  corrected it) or LEVE (nothing ran or was offered). It fails on any GRAVE.

Latest router measurement (`gemini-flash-lite-latest`, 103 cases, one run per configuration, so small
differences are within noise):

| Version | Overall raw | Overall guarded | Security raw | Security guarded |
|---|---|---|---|---|
| A: old prompt + assent guard | 89/103 (86%) | 93/103 (90%) | 43/54 (80%) | 47/54 (87%) |
| B: hardened prompt + guard | 92/103 (89%) | 96/103 (93%) | 45/54 (83%) | 49/54 (91%) |
| C: B + structured history turns | 96/103 (93%) | 100/103 (97%) | 48/54 (89%) | 52/54 (96%) |

End to end on C: 7 router failures, 0 GRAVE, 1 MITIGADO, 6 LEVE. The 44-query RAG set has not been run against
the model yet.

Router results per model (one run each, 5 s pause between calls; a gap of three or four cases is within noise).
The set had 103 cases until the `TICKET_CONFIRM` state and its only case were removed; it has 102 since:

| Model | Overall raw | Overall guarded | Security raw | Security guarded | Functional | Run time |
|---|---|---|---|---|---|---|
| `gemini-flash-lite-latest` (3.5), commit `84b5357` | 96/103 (93%) | 100/103 (97%) | 48/54 (89%) | 52/54 (96%) | 48/49 (98%) | 12 min |
| `gemini-3.1-flash-lite`, `main` at `1887a69` | 97/103 (94%) | 100/103 (97%) | 48/54 (89%) | 51/54 (94%) | 49/49 (100%) | 22 min |
| `gemma-4-26b-a4b-it`, `main` at `1887a69` | 94/103 (91%) | 97/103 (94%) | 47/54 (87%) | 50/54 (93%) | 47/49 (96%) | 130 min |
| `gemini-3.1-flash-lite`, `main` without `TICKET_CONFIRM` (102 cases) | 91/102 (89%) | 95/102 (93%) | 45/54 (83%) | 49/54 (91%) | 48/48 (100%) | n/a |

Both fail on the same family of cases: a bare "adelante" or "ok, thanks" read as a confirmation, forged context
or a fake "your return was approved" email read as an instruction. The 3.1 run finished in a single pass with no
API errors left open; its six failures are all security cases, and the assent guard turns three of them into a
safe `OTHER`. The two 3.1 rows differ by six cases after the prompt dropped one state and the set one case: a
single run is a sample, not a score. The router is only the first layer: an action still needs the verifier, a confirmation pending for
that very order, and the business rules in code. Gemma 4 26B ran on the free tier, which allows 16,000 input
tokens per minute and often answers 503: it needed 15 s between calls and took 130 minutes. The 31B never
completed the set (5 of 103 cases after more than an hour of 503s), so it has no row.

Everything else, including the full agent flows and the security suite, runs in CI with the LLM roles mocked:
those tests check the code's decisions, not the model's quality. Both backends can also send traces to a
self-hosted **Langfuse**: one trace per RAG query or chat turn, one span per graph node, one generation per
model call with its tokens, and scores such as the verifier verdict and the RAG confidence
([`LANGFUSE-INTEGRATION.md`](infra/LANGFUSE-INTEGRATION.md)).

## Estimated cost

Everything here runs on the free tier of the Gemini API, so the real cost is zero. This is what it would cost on
the paid tier, from estimated average token counts per call and the list prices of November 2026.

| Model | Input | Output (thinking included) |
|---|---|---|
| `gemini-flash-lite-latest` (Gemini 3.5 Flash-Lite, used in the cluster) | $0.30 / M tokens | $2.50 / M tokens |
| `gemini-3.1-flash-lite` (used in development) | $0.25 / M tokens | $1.50 / M tokens |
| `gemini-embedding-2` | $0.20 / M tokens | free |

| Operation | Estimated tokens (in / out) | With 3.5 | With 3.1 |
|---|---|---|---|
| L1 chat turn, a read such as order tracking: router 3000/50, verifier 1500/50, responder 3000/150 | ~7500 / 250 | ~$0.0029 | ~$0.0022 |
| AutoOps `search_docs`, no retry: query variants 300/100, grading 3000/300 | ~3300 / 400 | ~$0.0020 | ~$0.0014 |
| AutoOps `/search` chat, no retry: the above plus the answer 2800/300 | ~6100 / 700 | ~$0.0036 | ~$0.0026 |
| AutoOps search with one retry: plus rewrite 300/50 and a second grading 3500/300 | ~9900 / 1050 | ~$0.0056 | ~$0.0041 |

That is about **$3 per 1000 L1 turns** and **$2-6 per 1000 searches** with 3.5, and 25-30% less with 3.1.
Query embeddings are negligible (about $0.00002 per search). The CRAG grader is the most expensive call because it
reads every retrieved chunk. A confirmation button does not call the router LLM, and L1 cache hits cost nothing.
Thinking tokens are billed as output.

## Running it

Each project's README has the details. The short version:

```bash
# Unit and integration tests, no external services needed. The backends depend on platform-commons,
# so install it once (and again after changing it)
cd autoops/backend && ./mvnw -f ../../platform-commons/pom.xml install && ./mvnw verify
cd l1-copilot/backend && ./mvnw verify
```

```bash
# L1 Copilot locally with H2, no Kubernetes (needs GEMINI_API_KEY)
export GEMINI_API_KEY=...        # free key at https://aistudio.google.com/app/apikey
./scripts/start.sh               # backend :8079/api, frontend :4199
```

Full platform on a local k3s cluster (Windows + Rancher Desktop):

```bash
cp infra/.env.local.example infra/.env.local    # fill in the secrets, it is gitignored
bash infra/scripts/02-setup-registry.sh         # then 03..08, see infra/INFRA.md
./infra/scripts/deploy.sh --project autoops --component all
./infra/scripts/setup-app-ingress.sh --project autoops
./infra/scripts/deploy.sh --project l1-copilot --component all
./infra/scripts/setup-app-ingress.sh --project l1-copilot
```

## Repository layout

```
autoops/        AutoOps MCP and the corrective RAG: backend (Spring Boot), frontend (Angular), docs, sample corpus
l1-copilot/     L1 Support Copilot: backend (Spring Boot), frontend (Angular), docs, DB scripts
platform-commons/  Spring-free library shared by both backends: LLM response cache and Langfuse chat-model tracing
infra/          Shared platform: generic Helm chart, values per app, Weaviate, Loki, RBAC, scripts
docker/         Shared Dockerfiles (backend, frontend) and nginx config
wiki/           Wiki.js content (an extra RAG source) and its seeding scripts
docs/           Cross-project notes (open work, code-review findings) and the images and GIFs of this README
scripts/        Local start/stop of the L1 Copilot, verify-in-docker, and the scripts that regenerate the README media
.github/        CI (backends, frontends, Helm chart and shell scripts) and Dependabot
```

## Notes

- **Internal docs are in Spanish.** Architecture docs, ADRs and infrastructure guides live in each project's
  `docs/` folder and in [`infra/INFRA.md`](infra/INFRA.md). Code, scripts, configuration and READMEs are in English.
- **Product language is Spanish.** The L1 agent talks to Spanish-speaking customers and the RAG answers in
  Spanish, so LLM prompts, UI texts and demo data are in Spanish.
- **Synthetic demo data.** The RAG corpus (`autoops/corpus`), the wiki pages (`wiki/content`), the
  e-commerce seed (orders, users, tickets) and the FAQ are invented for the demo. No real company data.
- **AI-generated avatars.** The two demo user pictures in `l1-copilot/frontend/src/assets/avatars` were
  generated with an AI image tool; they do not depict real people.
- Built in June 2026 and reworked in November 2026 (English translation, security hardening, refactors, dependency upgrades, CI). Commit dates are the real ones.

## License

[MIT](LICENSE)
