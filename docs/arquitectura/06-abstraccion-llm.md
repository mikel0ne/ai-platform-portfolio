# 06 — Abstracción del proveedor LLM

Decisión marco (ADR-003): el código de negocio del agente **no sabe qué proveedor LLM usa**. La implementación por defecto es **Gemini (Google AI Studio, capa gratuita)**.

## 1. El principio

LangChain4j ya proporciona las interfaces neutrales; la regla del proyecto es que **ninguna clase fuera de `io.github.mikel0ne.autoops.agent.llm.provider` importe clases de un proveedor concreto**:

| Interfaz (la que ven nodos y servicios) | Implementación Gemini (solo en `llm/provider`) |
|---|---|
| `dev.langchain4j.model.chat.ChatModel` | `GoogleAiGeminiChatModel` |
| `dev.langchain4j.model.embedding.EmbeddingModel` | `GoogleAiEmbeddingModel` (`text-embedding-004`) |

Los nodos del grafo (doc 03) reciben `ChatModel` por constructor. Cambiar de proveedor no los toca.

## 2. Configuración tipada

```java
package io.github.mikel0ne.autoops.agent.llm;

@Validated
@ConfigurationProperties("autoops.llm")
public record LlmProperties(
        @NotBlank String provider,      // gemini | (futuros: azure-openai, ollama...)
        @NotBlank String chatModelName,
        @NotBlank String embeddingModelName,
        @DurationUnit(ChronoUnit.SECONDS) Duration timeout,
        Double temperature
) {}
```

`application.yml` del agente:

```yaml
autoops:
  llm:
    provider: gemini
    chat-model-name: gemini-2.5-flash-lite     # mayor cuota gratuita
    embedding-model-name: text-embedding-004   # 768 dims (acoplado al DDL de rag.architecture_docs)
    timeout: 30s
    temperature: 0.2          # bajo: generación de YAML, no creatividad
```

La API key **no** va en YAML: variable de entorno `GEMINI_API_KEY` (en K8s, desde el Secret `autoops-llm-secret`).

## 3. Implementación por proveedor: un `@Configuration` condicional

```java
package io.github.mikel0ne.autoops.agent.llm.provider;

@Configuration
@ConditionalOnProperty(name = "autoops.llm.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiModelConfig {

    @Bean
    public ChatModel chatModel(LlmProperties props,
                               @Value("${GEMINI_API_KEY}") String apiKey) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(props.chatModelName())
                .temperature(props.temperature())
                .timeout(props.timeout())
                .logRequestsAndResponses(true)   // a nivel DEBUG; sin payloads en INFO
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(LlmProperties props,
                                         @Value("${GEMINI_API_KEY}") String apiKey) {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(props.embeddingModelName())
                .build();
    }
}
```

## 4. Añadir un proveedor nuevo (receta completa)

Ejemplo: Azure OpenAI. Total: 1 dependencia + 1 clase + configuración. Cero cambios en grafo, nodos, RAG o API.

1. **Dependencia** en `agent/pom.xml`:

```xml
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-azure-open-ai</artifactId>
</dependency>
```

2. **Clase** `AzureOpenAiModelConfig` junto a la de Gemini:

```java
@Configuration
@ConditionalOnProperty(name = "autoops.llm.provider", havingValue = "azure-openai")
public class AzureOpenAiModelConfig {

    @Bean
    public ChatModel chatModel(LlmProperties props,
                               @Value("${AZURE_OPENAI_API_KEY}") String apiKey,
                               @Value("${AZURE_OPENAI_ENDPOINT}") String endpoint) {
        return AzureOpenAiChatModel.builder()
                .apiKey(apiKey)
                .endpoint(endpoint)
                .deploymentName(props.chatModelName())   // p.ej. gpt-4o
                .temperature(props.temperature())
                .timeout(props.timeout())
                .build();
    }
    // + EmbeddingModel equivalente
}
```

3. **Configuración** (despliegue): `AUTOOPS_LLM_PROVIDER=azure-openai`, nuevas claves en el Secret `autoops-llm-secret` y model names en el YAML.

4. **Atención a los embeddings**: si el modelo de embeddings cambia de dimensión (Gemini 768 vs `text-embedding-3-small` 1536), hay que recrear `rag.architecture_docs` con la nueva dimensión y re-ingestar el corpus. El chat, en cambio, es intercambiable sin migración.

## 5. El proveedor falso para tests

La misma mecánica da el tercer "proveedor": el de tests (doc 03 §7). No requiere `@Configuration` condicional: en tests se construyen los nodos a mano con un stub.

```java
// src/test/java — stub programable
class ScriptedChatModel implements ChatModel {
    private final Deque<String> responses;
    ScriptedChatModel(String... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(responses.poll()))
                .build();
    }
}
```

Con esto, el test del ciclo de auto-corrección programa: `yaml-malo`, `yaml-malo`, `yaml-bueno` y verifica que el grafo itera 3 veces y termina en `ok`.

## 6. Reglas de oro

1. Ningún `import dev.langchain4j.model.googleai.*` (ni de otro vendor) fuera de `llm/provider`.
2. La temperatura, modelo y timeout se leen siempre de `LlmProperties` — nunca hardcodeados en un nodo.
3. Una llamada LLM siempre lleva timeout (la cadena de timeouts del doc 05 §5 depende de ello).
4. Los logs nunca incluyen la API key ni payloads completos en niveles ≥ INFO.
5. La cuota gratuita de Gemini se respeta con: modelo `flash-lite` por defecto, temperatura baja (menos reintentos), y caché de respuestas opcional (heredable del código semilla `FileCacheManager`) **solo** para desarrollo local, nunca para la ruta `diagnose` (el estado del clúster cambia).
