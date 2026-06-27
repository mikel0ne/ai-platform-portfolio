# 04 — Organización del código

## 1. Estructura del repositorio

El repo es un **monorepo de portfolio**: AutoOps vive en `autoops/` junto a otros proyectos (p. ej.
`l1-copilot/`), y comparte la plataforma de despliegue (`infra/`, `docker/`, `scripts/`, `.run/`) en la raíz. El
backend (servidor MCP) es **una sola aplicación Spring Boot** en `autoops/backend/`:

```
/
├── autoops/
│   ├── backend/                     # servidor MCP (Spring Boot · Spring AI MCP · LangChain4j · LangGraph4j · fabric8)
│   │   ├── pom.xml
│   │   └── src/main/java/io/github/mikel0ne/autoops/...
│   ├── frontend/                    # chat de búsqueda (Angular, reutilizado del scaffolding)
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
├── docker/                          # backend.Dockerfile · frontend.Dockerfile, COMPARTIDOS (multi-stage genéricos)
├── scripts/  .run/                  # deploy.sh / setup-app-ingress.sh genéricos por `--project`; run configs IntelliJ
├── llms-lc4j.txt  llms-lgraph.txt   # referencias LangChain4j / LangGraph4j (raíz, aplican a todo el monorepo)
├── CLAUDE.md
└── README.md
```

**Sobre el scaffolding reutilizado:** AutoOps reaprovechó, adaptado al paquete `io.github.mikel0ne.autoops`, la capa de
plataforma de un proyecto previo del mismo monorepo (build Maven, configuración del modelo Gemini abstraída,
caché de respuestas LLM para desarrollo, manejador de errores RFC 7807), el chart Helm `generic-app`, los
Dockerfiles multi-stage y el proyecto Angular como base del chat de búsqueda. La capa de dominio de ese
proyecto previo se eliminó.

> El chat de búsqueda se sirve como **SPA Angular** (`autoops/frontend/src/app/{core,search}`), ya podada de
> las pantallas heredadas del scaffolding (login, returns, ticket): solo quedan `core/` y `search/`.

## 2. `pom.xml`: dependencias clave

```xml
<groupId>io.github.mikel0ne</groupId>
<artifactId>autoops-mcp</artifactId>

<properties>
  <java.version>17</java.version>
  <spring-ai.version><!-- última estable --></spring-ai.version>
  <langchain4j.version><!-- última 1.x --></langchain4j.version>
  <langgraph4j.version><!-- última 1.x --></langgraph4j.version>
</properties>

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
</dependencies>
```

> Para uso **local por stdio** en lugar de Streamable HTTP, se usa el starter `spring-ai-starter-mcp-server`
> (sin WebMVC). El catálogo de tools es el mismo; solo cambia el transporte.

## 3. Paquetes (`io.github.mikel0ne.autoops`)

Organización **por característica**, no por capa técnica:

```
io.github.mikel0ne.autoops
├── Application.java
├── tools/                     # ←— las tools que ve el cliente MCP (doc 03)
│   ├── DiagnosticsTools.java        # @Tool ping
│   ├── DocsTools.java               # @Tool buscar_docs
│   ├── KubernetesTools.java         # @Tool diagnosticar_pod, estado_pods, listar_workloads, describir_pod, eventos, logs_pod
│   ├── ObservabilityTools.java      # @Tool consultar_logs
│   └── McpConfig.java               # ToolCallbackProvider (registra las 4 anteriores)
├── rag/                       # núcleo RAG compartido (tool + chat)
│   ├── RagService.java              # API interna: buscar(consulta, k)
│   ├── DocRetriever.java            # interfaz que implementa WeaviateGateway (testable con un doble)
│   ├── DocChunk.java  Chunk.java  RagResult.java
│   ├── Chunker.java                 # static/puro: heading+recursivo, ventana de vecinos (ver ADR-010)
│   ├── IngestionService.java  IngestionController.java  IngestionProperties.java   # fuentes → chunks → embeddings → Weaviate
│   ├── source/                      # orígenes de documentación (ver ADR-010)
│   │   ├── FuenteDocumental.java  Documento.java
│   │   ├── LocalMarkdownSource.java # corpus/*.md local
│   │   ├── GitMarkdownSource.java   # clona un repo (JGit) y permalinkea al SHA
│   │   └── ConfluenceSource.java    # stub: registrado, leer() vacío hasta tener instancia
│   ├── WeaviateConfig.java          # bean WeaviateClient (cliente raw, ver ADR-005)
│   ├── WeaviateGateway.java         # esquema + ingesta + retrieve (híbrido+rerank, query GraphQL a mano)
│   ├── WeaviateProperties.java
│   └── crag/                        # bucle correctivo (LangGraph4j)
│       ├── CragState.java  CragGraph.java  CragProperties.java   # k/maxIteraciones/alpha/kStep/kMax — sin "umbral"
│       └── nodes/ (RetrieveNode, RerankNode, GradeNode, RewriteNode)
├── chat/                      # front secundario de búsqueda
│   ├── SearchController.java        # POST /search
│   ├── SearchRequest.java  SearchResponse.java
│   └── AnswerComposer.java          # redacción con LLM interno (anti-alucinación)
├── k8s/                       # inspección read-only del clúster
│   ├── K8sClientConfig.java         # fabric8 (in-cluster / kubeconfig)
│   ├── K8sInspectionService.java    # dossiers: diagnose(), logs(), events()...
│   └── model/ (PodDiagnosis, PodStatus, WorkloadStatus, PodDescription)
├── observability/             # logs agregados (Loki), complemento de logs_pod (doc 03 §1.3)
│   ├── LokiConfig.java              # bean RestClient
│   ├── LokiGateway.java             # construye LogQL, resuelve ventana temporal, aplana la respuesta
│   ├── LokiProperties.java
│   └── model/LogEntry.java
├── llm/provider/              # abstracción de proveedor (doc 06) — única clase que conoce el vendor
│   └── GeminiModelConfig.java
├── config/                    # configuración tipada transversal
│   ├── LlmProperties.java           # (no en llm/: es config leída por GeminiModelConfig, no parte del SPI de proveedor)
│   ├── CacheProperties.java  CacheInitializer.java  PromptLoader.java
├── cache/                     # caché de respuestas LLM (desarrollo, evitar quemar cuota)
│   ├── CachingChatModel.java  ChatModelCachePostProcessor.java  FileCacheManager.java
└── controller/
    └── ApiExceptionHandler.java     # RFC 7807

src/main/resources/
├── application.yml                  # + application-k8s.yml
└── prompts/                         # plantillas (nunca prompts hardcodeados)
    ├── crag_rewrite.txt             # reformular/expandir consulta
    ├── crag_grade.txt               # calificar relevancia (GradeNode, siempre vía LLM)
    └── chat_answer.txt              # redacción del chat

frontend/src/app/
├── core/                       # servicios/modelos compartidos de la SPA
└── search/                     # única pantalla: el chat de búsqueda (sin login/returns/ticket heredados)
```

Reglas de dependencia internas: `tools/chat → {rag, k8s, observability} → {llm/provider, weaviate, fabric8, loki}`.
Las clases del bucle CRAG no conocen Spring (POJOs instanciados en config): se testean sin contexto.

## 4. Convenciones transversales

- **Idioma**: código e identificadores en inglés; nombres de tools y documentación de cara al usuario en
  español (`buscar_docs`, `diagnosticar_pod`), por ser lo que ve quien usa el asistente.
- **DTOs como `record`**: `DocChunk`, `PodDiagnosis`, `SearchRequest`/`SearchResponse`.
- **Configuración tipada**: `@ConfigurationProperties` con `record` + `@Validated`; nada de `@Value` suelto.
- **Prompts en `resources/prompts/`**, uno por fichero, versionados con el código.
- **Tools**: descripciones claras y orientadas a la acción (el cliente MCP decide por la descripción cuándo
  llamarlas); salidas **estructuradas** (records → JSON), no texto libre cuando se puede evitar.
- **Tests**: JUnit 5 + AssertJ; `KubernetesServer` de fabric8 para K8s; ningún test depende de un LLM real ni
  de un clúster real (doc 03 §5).
- **Errores**: las tools devuelven mensajes accionables; los fallos de acceso a K8s se traducen a un texto
  claro para que el cliente lo entienda (p. ej. "pod no encontrado en el namespace X").
- **Ramas**: `feature/<fase>-<descripción>` según el roadmap (doc 07).
