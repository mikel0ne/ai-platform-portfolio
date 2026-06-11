# 04 — Organización del código

## 1. Estrategia: monorepo

Todo AutoOps vive en este repositorio, organizado por servicio. Razones: el proyecto es de una persona, los contratos entre servicios evolucionan a la vez, y un solo repo cuenta mejor la historia en el porfolio.

```
/
├── .github/
│   └── copilot-instructions.md      # reglas de IA del repo (existente)
├── docs/
│   ├── arquitectura/                # esta documentación
│   └── infraestructura/             # guías K8s (rama autoops/docs-infraestructura)
├── k8s/                             # manifiestos (extraídos de docs/infraestructura)
│   ├── 00-namespace.yaml
│   ├── 01-secrets.template.yaml     # 01-secrets.yaml está en .gitignore
│   ├── 02-rbac-agent.yaml
│   ├── 10-postgres.yaml
│   ├── 20-agent.yaml
│   ├── 21-gateway.yaml
│   ├── 22-frontend.yaml
│   └── 30-ingress.yaml
├── agent/                           # Spring Boot · LangChain4j · LangGraph4j
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/...
├── gateway/                         # Spring Boot · negocio
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/...
├── frontend/                        # Angular
│   ├── Dockerfile
│   ├── nginx.conf
│   └── src/...
├── corpus/                          # documentación interna a indexar por el RAG (markdown)
├── llms-lc4j.txt                    # referencia LangChain4j (existente)
├── llms-lgraph.txt                  # referencia LangGraph4j (existente)
└── pom.xml                          # POM padre (módulos agent y gateway)
```

**Sobre el código existente** (`src/main/java/io/github/mikel0ne/l1copilot/...`): es la semilla/spike de integración con Gemini. En la fase F0 del roadmap se migra al módulo `agent` (config del modelo, caché de respuestas) y el árbol `src/` raíz desaparece. El `groupId` pasa a `io.github.mikel0ne.autoops`.

## 2. POM padre

Maven multi-módulo para compartir versiones (Spring Boot, LangChain4j, LangGraph4j) y construir todo con un comando:

```xml
<groupId>io.github.mikel0ne</groupId>
<artifactId>autoops-parent</artifactId>
<packaging>pom</packaging>

<modules>
  <module>agent</module>
  <module>gateway</module>
</modules>

<properties>
  <java.version>17</java.version>
  <langchain4j.version><!-- última 1.x estable --></langchain4j.version>
  <langgraph4j.version><!-- última 1.x estable --></langgraph4j.version>
</properties>
```

(El frontend no es módulo Maven; su build es npm y vive en `frontend/`.)

## 3. Paquetes del módulo `agent`

Paquete raíz `io.github.mikel0ne.autoops.agent`, organizado **por característica** (no por capa técnica):

```
io.github.mikel0ne.autoops.agent
├── AgentApplication.java
├── api/                       # capa REST interna que consume el gateway
│   ├── AgentController.java         # POST /agent/run · POST /agent/rag/ingest
│   └── dto/                         # AgentRequest, AgentResponse, IngestRequest...
├── graph/                     # ←— el corazón (doc 03)
│   ├── AutoOpsState.java
│   ├── AutoOpsGraphBuilder.java
│   ├── AgentRunService.java         # compila y ejecuta el grafo
│   └── nodes/
│       ├── RouterNode.java
│       ├── RetrieveDocsNode.java
│       ├── DiagnoseNode.java
│       ├── GenerateYamlNode.java
│       ├── ValidateYamlNode.java
│       └── ComposeAnswerNode.java
├── llm/                       # abstracción del proveedor (doc 06)
│   ├── LlmProperties.java           # @ConfigurationProperties("autoops.llm")
│   └── provider/
│       ├── GeminiModelConfig.java   # @ConditionalOnProperty(... provider=gemini)
│       └── (futuros proveedores)
├── rag/
│   ├── EmbeddingStoreConfig.java    # PgVectorEmbeddingStore (esquema rag)
│   └── IngestionService.java        # corpus/*.md → chunks → embeddings
├── k8s/
│   ├── K8sClientConfig.java         # fabric8 KubernetesClient (in-cluster / kubeconfig)
│   └── K8sInspectionService.java    # dossier de diagnóstico read-only
├── validation/
│   ├── YamlValidationService.java
│   ├── ValidationResult.java
│   └── rules/                       # una clase por política (ADR-007)
│       ├── PolicyRule.java          # interfaz
│       ├── RequireResourceLimitsRule.java
│       ├── ForbidLatestTagRule.java
│       ├── RequireProbesRule.java
│       ├── ForbidPrivilegedRule.java
│       └── RequirePartOfLabelRule.java
└── config/
    └── GraphConfig.java             # beans de nodos y CompiledGraph

src/main/resources/
├── application.yml                  # + application-k8s.yml
└── prompts/                         # plantillas (nunca prompts hardcodeados)
    ├── router.txt
    ├── generate_yaml.txt
    ├── correct_yaml.txt
    ├── compose_docs.txt
    ├── compose_diagnose.txt
    └── compose_generate.txt
```

Reglas de dependencia internas: `api → graph → {llm, rag, k8s, validation}`. Los nodos no conocen Spring (clases planas instanciadas en `GraphConfig`): se testean sin contexto.

## 4. Paquetes del módulo `gateway`

Paquete raíz `io.github.mikel0ne.autoops.gateway`:

```
io.github.mikel0ne.autoops.gateway
├── GatewayApplication.java
├── api/
│   ├── ChatController.java          # POST /api/chat
│   ├── ConversationController.java  # GET/POST /api/conversations...
│   ├── AuthController.java          # POST /api/auth/login
│   └── dto/
├── chat/                      # dominio: conversaciones e historial
│   ├── ConversationService.java
│   ├── Conversation.java / Message.java        # entidades JPA (esquema chat)
│   └── ConversationRepository.java / MessageRepository.java
├── agentclient/               # el agente como caja negra
│   ├── AgentClient.java             # RestClient con timeout 120s
│   └── AgentClientProperties.java   # autoops.agent.base-url
├── audit/
│   ├── AuditService.java
│   └── AuditLog.java / AuditLogRepository.java
├── security/
│   ├── SecurityConfig.java          # filtros JWT, rutas públicas/privadas
│   ├── JwtService.java
│   └── UserProperties.java          # usuarios estáticos del MVP
└── config/
    └── OpenApiConfig.java
```

## 5. Estructura del `frontend`

Angular 18+ standalone components, organizado por feature:

```
frontend/src/app
├── core/                      # singleton: auth, interceptores, cliente API
│   ├── api/ (servicios HTTP generados sobre /api)
│   ├── auth/ (login, guardia, interceptor JWT)
│   └── layout/ (shell del dashboard)
├── features/
│   ├── chat/                  # conversación, render markdown/YAML, badges de ruta/iteraciones
│   └── history/               # lista de conversaciones
└── shared/                    # componentes UI puros (code-block, badge, spinner)
```

## 6. Convenciones transversales

- **Idioma**: código e identificadores en inglés; documentación y mensajes de UI en español.
- **DTOs como `record`** en ambos servicios; entidades JPA solo en `gateway/chat` y `gateway/audit`.
- **Configuración tipada**: `@ConfigurationProperties` con `record` + validación (`@Validated`); nada de `@Value` suelto.
- **Prompts en `resources/prompts/`**, uno por fichero, versionados con el código.
- **Lombok**: permitido en gateway (`@RequiredArgsConstructor`, `@Slf4j`); en el módulo agent se prefiere código explícito en las clases del grafo (favorece la legibilidad del porfolio).
- **Tests**: JUnit 5 + AssertJ; Testcontainers para PostgreSQL; ningún test depende de un LLM real (doc 03 §7).
- **Errores**: los servicios devuelven Problem Details (RFC 7807, soporte nativo Spring Boot 3: `spring.mvc.problemdetails.enabled=true`).
- **Ramas**: `feature/<fase>-<descripción>` según el roadmap (doc 07); merge a `main` con la fase verificada.
