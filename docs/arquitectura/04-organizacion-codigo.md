# 04 — Organización del código

## 1. Estrategia: un solo proyecto

AutoOps es **una sola aplicación Spring Boot** (no un monorepo de varios servicios: ya no hay gateway ni
frontend de copiloto). El repositorio:

```
/
├── .github/
│   └── copilot-instructions.md      # reglas de IA del repo (existente)
├── docs/
│   ├── arquitectura/                # esta documentación
│   └── infraestructura/             # guías de despliegue en K8s
├── k8s/                             # manifiestos (extraídos de docs/infraestructura)
│   ├── 00-namespace.yaml
│   ├── 01-secrets.template.yaml     # 01-secrets.yaml está en .gitignore
│   ├── 02-rbac.yaml                 # ServiceAccount + ClusterRole read-only
│   ├── 10-weaviate.yaml
│   ├── 20-autoops-mcp.yaml
│   └── 30-ingress.yaml
├── corpus/                          # documentación interna a indexar por el RAG (markdown)
├── src/                             # código de la aplicación (un solo módulo)
├── Dockerfile
├── llms-lc4j.txt                    # referencia LangChain4j (existente)
├── llms-lgraph.txt                  # referencia LangGraph4j (existente)
└── pom.xml
```

**Sobre el código existente** (`src/main/java/io/github/mikel0ne/l1copilot/...`): es la semilla de integración con
Gemini. En la fase F0 (doc 07) se reaprovecha la configuración del modelo y se reubica bajo el paquete
`io.github.mikel0ne.autoops`; el `groupId` pasa a `io.github.mikel0ne.autoops`.

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
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>   <!-- transporte HTTP/SSE -->
  </dependency>

  <!-- Motor RAG -->
  <dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-weaviate</artifactId>                  <!-- WeaviateEmbeddingStore -->
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

> Para uso **local por stdio** en lugar de HTTP/SSE, se usa el starter `spring-ai-starter-mcp-server` (sin
> WebMVC). El catálogo de tools es el mismo; solo cambia el transporte.

## 3. Paquetes (`io.github.mikel0ne.autoops`)

Organización **por característica**, no por capa técnica:

```
io.github.mikel0ne.autoops
├── AutoOpsApplication.java
├── tools/                     # ←— las tools que ve el cliente MCP (doc 03)
│   ├── DocsTools.java               # @Tool buscar_docs
│   ├── KubernetesTools.java         # @Tool diagnosticar_pod, estado_pods, logs_pod, eventos...
│   └── McpConfig.java               # ToolCallbackProvider
├── rag/                       # núcleo RAG compartido (tool + chat)
│   ├── RagService.java              # API interna: buscar(consulta, k)
│   ├── IngestionService.java        # corpus/*.md → chunks → embeddings → Weaviate
│   ├── WeaviateConfig.java          # WeaviateEmbeddingStore + reranker
│   └── crag/                        # bucle correctivo (LangGraph4j)
│       ├── CragState.java
│       ├── CragGraph.java
│       └── nodes/ (Retrieve, Rerank, Grade, Rewrite)
├── chat/                      # front secundario de búsqueda
│   ├── SearchController.java        # POST /search
│   └── AnswerComposer.java          # redacción con LLM interno (anti-alucinación)
├── k8s/                       # inspección read-only del clúster
│   ├── K8sClientConfig.java         # fabric8 (in-cluster / kubeconfig)
│   └── K8sInspectionService.java    # dossiers: diagnose(), logs(), events()...
├── llm/                       # abstracción de proveedor (doc 06)
│   ├── LlmProperties.java
│   └── provider/GeminiModelConfig.java
└── web/                       # UI mínima estática del chat (no es un framework SPA)

src/main/resources/
├── application.yml                  # + application-k8s.yml
├── static/                          # index.html del chat de búsqueda (HTML+JS mínimos)
└── prompts/                         # plantillas (nunca prompts hardcodeados)
    ├── crag_rewrite.txt             # reformular/expandir consulta
    └── chat_answer.txt              # redacción del chat
```

Reglas de dependencia internas: `tools/chat → {rag, k8s} → {llm, weaviate, fabric8}`. Las clases del bucle CRAG
no conocen Spring (POJOs instanciados en config): se testean sin contexto.

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
