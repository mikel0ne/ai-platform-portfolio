# 04 — Organización del código

## 1. Estructura del repositorio

El repo es un **monorepo de portfolio**: AutoOps vive en `autoops/` junto a otros proyectos (p. ej.
`l1-copilot/`), y comparte la plataforma de despliegue (`infra/`, `docker/`, `scripts/`, `.run/`) en la raíz. El
backend (servidor MCP) es **una sola aplicación Spring Boot** en `autoops/backend/`:

```
/
├── pom.xml                          # agregador del reactor: platform-commons, l1-copilot/backend, autoops/backend
├── platform-commons/                # librería sin Spring compartida por ambos backends (caché de respuestas LLM y trazas Langfuse)
├── autoops/
│   ├── backend/                     # servidor MCP (Spring Boot · Spring AI MCP · LangChain4j · LangGraph4j · fabric8)
│   │   ├── pom.xml  lombok.config  mvnw  .mvn/
│   │   └── src/main/java/io/github/mikel0ne/autoops/...
│   ├── frontend/                    # chat de búsqueda (Angular)
│   ├── corpus/                      # documentación interna a indexar por el RAG (markdown)
│   └── docs/{arquitectura,infraestructura}/
├── l1-copilot/                      # otro proyecto del portfolio (mismo monorepo, plataforma compartida)
├── infra/                           # despliegue, COMPARTIDO entre proyectos
│   ├── charts/generic-app/          # chart Helm genérico reutilizable
│   ├── components/weaviate/         # Weaviate compartido (ns infra) + reranker-transformers
│   ├── observability/               # Loki + Promtail + Grafana (ns observability)
│   ├── projects/<proyecto>.env      # descriptor por proyecto (namespace, imágenes, paths) que consume deploy.sh
│   ├── values-autoops-{backend,frontend}.yaml
│   ├── namespace-autoops.yaml
│   └── rbac-autoops.yaml            # SA read-only para las tools K8s
├── docker/                          # backend.Dockerfile · frontend.Dockerfile, COMPARTIDOS (multi-stage genéricos; el backend recibe platform-commons como build context `commons`)
├── scripts/  .run/                  # arranque local de L1, verify-in-docker.sh; run configs IntelliJ (infra/scripts/ tiene deploy.sh y setup-app-ingress.sh genéricos por `--project`)
└── README.md
```

**Sobre el scaffolding reutilizado:** AutoOps reaprovechó, adaptado al paquete `io.github.mikel0ne.autoops`, la capa de
plataforma de un proyecto previo del mismo monorepo (build Maven, configuración del modelo Gemini abstraída,
manejador de errores RFC 7807), el chart Helm `generic-app`, los Dockerfiles multi-stage y el proyecto Angular
como base del chat de búsqueda. La caché de respuestas LLM y las trazas Langfuse comunes viven hoy en
`platform-commons`. La capa de dominio de ese proyecto previo se eliminó.

> El chat de búsqueda se sirve como **SPA Angular** (`autoops/frontend/src/app/{core,search}`): solo `core/` y
> `search/`.

## 2. `pom.xml`: dependencias clave

```xml
<groupId>io.github.mikel0ne</groupId>
<artifactId>autoops-mcp</artifactId>

<properties>
  <java.version>17</java.version>
  <spring-ai.version>2.0.2</spring-ai.version>
  <langchain4j.version>1.23.0</langchain4j.version>   <!-- langgraph4j 1.8.x compila contra langchain4j 1.14+: subirlos a la vez -->
  <langgraph4j.version>1.8.27</langgraph4j.version>
  <fabric8.version>6.14.0</fabric8.version>
</properties>
<!-- Spring Boot 4.1.2 como parent -->

<dependencies>
  <!-- Protocolo MCP (servidor) -->
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>   <!-- transporte HTTP (Streamable HTTP, Spring AI 2.x) -->
  </dependency>

  <!-- Motor RAG -->
  <dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-weaviate</artifactId>                  <!-- trae io.weaviate:client (cliente raw, ver ADR-005); WeaviateEmbeddingStore NO se usa -->
  </dependency>

  <!-- Bucle correctivo (grafo cíclico) -->
  <dependency>
    <groupId>org.bsc.langgraph4j</groupId>
    <artifactId>langgraph4j-core</artifactId>
  </dependency>

  <!-- Cliente Kubernetes -->
  <dependency>
    <groupId>io.fabric8</groupId>
    <artifactId>kubernetes-client</artifactId>
  </dependency>

  <!-- Librería compartida sin Spring: CachingChatModel/FileCacheManager y el listener de Langfuse -->
  <dependency>
    <groupId>io.github.mikel0ne</groupId>
    <artifactId>platform-commons</artifactId>
    <version>1.0.0</version>
  </dependency>

  <!-- Lombok (optional) -->
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
  </dependency>
</dependencies>
```

> `platform-commons` debe estar instalado en el repositorio Maven local antes de compilar un backend a mano
> (`./mvnw -f ../../platform-commons/pom.xml install`), o se construye todo desde el `pom.xml` raíz (reactor).
> Con el starter `spring-ai-starter-mcp-server` (sin WebMVC) el mismo catálogo de tools podría servirse por
> stdio; no está configurado.

## 3. Paquetes (`io.github.mikel0ne.autoops`)

Organización **por característica**, no por capa técnica:

```
io.github.mikel0ne.autoops
├── Application.java
├── tools/                     # ←— las tools que ve el cliente MCP (doc 03)
│   ├── DiagnosticsTools.java        # @Tool ping
│   ├── DocsTools.java               # @Tool search_docs
│   ├── KubernetesTools.java         # @Tool diagnose_pod, list_pods, list_workloads, describe_pod, list_events, pod_logs
│   ├── ObservabilityTools.java      # @Tool query_logs
│   └── McpConfig.java               # ToolCallbackProvider (registra las 4 clases anteriores)
├── rag/                       # núcleo RAG compartido (tool + chat)
│   ├── RagService.java              # API interna: search(query, k) → RagResult (chunks, iterations, confidence)
│   ├── DocRetriever.java            # interfaz que implementa WeaviateGateway (testable con un doble)
│   ├── DocChunk.java  Chunk.java  RagResult.java  Confidence.java
│   ├── Chunker.java                 # static/puro: heading+recursivo; almacena el núcleo + sectionChars (ver ADR-010/011)
│   ├── IngestionService.java  IngestionController.java  IngestionProperties.java   # fuentes → chunks → embeddings → Weaviate
│   ├── source/                      # orígenes de documentación (ver ADR-010)
│   │   ├── DocumentSource.java  SourceDocument.java
│   │   ├── LocalMarkdownSource.java # corpus/*.md local
│   │   ├── GitHubMarkdownSource.java# repositorio de GitHub leído por su API (GitHubGateway, sin clonar)
│   │   └── WikiJsSource.java        # páginas de Wiki.js (wiki self-hosted) vía su API GraphQL (wikijs/)
│   ├── WeaviateConfig.java          # bean WeaviateClient (cliente raw, ver ADR-005)
│   ├── WeaviateGateway.java         # esquema + ingesta + retrieve (híbrido+rerank, query GraphQL a mano)
│   ├── WeaviateProperties.java
│   └── crag/                        # bucle correctivo (LangGraph4j)
│       ├── CragState.java  CragGraph.java  CragProperties.java   # k/maxIterations/alpha/kStep/kMax/multiQueryN/retrievalParallelism
│       ├── ChunkVerdict.java  Coverage.java
│       ├── llm/ (Grader, GradeResult, QueryVariantGenerator)   # AiServices del calificador y de las variantes
│       └── nodes/ (MultiQueryNode, RetrieveNode, TopKNode, GradeNode, ExpandNode, RewriteNode, RrfFusion, DocChunkDedup)
├── chat/                      # front secundario de búsqueda
│   ├── SearchController.java        # POST /search
│   ├── SearchRequest.java  SearchResponse.java
│   ├── SearchRateLimiter.java  SearchProperties.java   # tope global en memoria de peticiones por minuto (una réplica)
│   └── AnswerComposer.java          # redacción con LLM interno (anti-alucinación)
├── k8s/                       # inspección read-only del clúster
│   ├── K8sClientConfig.java         # fabric8 (in-cluster / kubeconfig)
│   ├── K8sInspectionService.java    # dossiers: diagnose(), logs(), events()...
│   └── model/ (PodDiagnosis, PodStatus, WorkloadStatus, PodDescription)
├── observability/             # logs agregados (Loki), complemento de pod_logs (doc 03 §1.3)
│   ├── LokiConfig.java              # bean RestClient
│   ├── LokiGateway.java             # construye LogQL, resuelve ventana temporal, aplana la respuesta
│   ├── LokiProperties.java
│   └── model/LogEntry.java
├── observability/langfuse/    # trazas y scores en Langfuse, ligado a Spring (el listener y el trazador de cache hits viven en platform-commons)
│   └── LangfuseTracing / LangfuseTracingConfig / LangfuseProperties / LangfuseScoreGateway
├── github/                    # GitHubGateway (API de GitHub para la ingesta)
├── wikijs/                    # WikiJsGateway (GraphQL) para WikiJsSource
├── security/                  # AdminTokenFilter: X-Admin-Token en /admin/*, X-MCP-Token en /mcp
├── llm/provider/              # abstracción de proveedor (doc 06) — única clase que conoce el vendor
│   └── GeminiModelConfig.java
├── config/                    # configuración tipada transversal
│   ├── LlmProperties.java           # (no en llm/: es config leída por GeminiModelConfig, no parte del SPI de proveedor)
│   ├── CacheProperties.java  CacheConfig.java   # caché de respuestas LLM solo para desarrollo (CachingChatModel y FileCacheManager viven en platform-commons)
│   ├── PromptLoader.java  PromptTemplates.java  # cargan y cachean los prompts de resources/prompts
│   ├── CharsetGuard.java            # el arranque falla si el charset por defecto de la JVM no es UTF-8
│   └── HttpClientProperties.java  RestClients.java
└── error/
    ├── ApiExceptionHandler.java     # RFC 7807 sobre ResponseEntityExceptionHandler
    └── NotFoundException.java  InvalidRequestException.java

src/main/resources/
├── application.yml                  # + application-k8s.yml
└── prompts/                         # plantillas (nunca prompts hardcodeados)
    ├── crag_multiquery.txt          # variantes de la consulta (primera vuelta)
    ├── crag_rewrite.txt             # reformular/expandir consulta
    ├── crag_grade.txt               # calificar relevancia (GradeNode, siempre vía LLM)
    └── chat_answer.txt              # redacción del chat

frontend/src/app/
├── core/                       # servicios/modelos compartidos de la SPA
└── search/                     # única pantalla: el chat de búsqueda
```

Reglas de dependencia internas: `tools/chat → {rag, k8s, observability} → {llm/provider, weaviate, fabric8, loki}`.
Las clases del bucle CRAG no conocen Spring (POJOs instanciados en config): se testean sin contexto.

## 4. Convenciones transversales

- **Idioma**: código, identificadores, parámetros y descripciones de las tools en inglés (`search_docs`,
  `diagnose_pod`); la documentación de diseño en español; prompts y corpus de demo en español.
- **DTOs como `record`**: `DocChunk`, `PodDiagnosis`, `SearchRequest`/`SearchResponse`.
- **Configuración tipada**: `@ConfigurationProperties` con `record` + `@Validated`; nada de `@Value` suelto.
- **Prompts en `resources/prompts/`**, uno por fichero, versionados con el código.
- **Tools**: descripciones claras y orientadas a la acción (el cliente MCP decide por la descripción cuándo
  llamarlas); salidas **estructuradas** (records → JSON), no texto libre cuando se puede evitar.
- **Tests**: JUnit 5 + AssertJ; `KubernetesServer` de fabric8 para K8s; ningún test depende de un LLM real ni
  de un clúster real (doc 03 §5).
- **Errores**: las tools devuelven mensajes accionables; los fallos de acceso a K8s se traducen a un texto
  claro para que el cliente lo entienda (p. ej. "pod no encontrado en el namespace X").
- **Lombok**: `@RequiredArgsConstructor`/`@Slf4j` en servicios y componentes (`lombok.config` por módulo).
