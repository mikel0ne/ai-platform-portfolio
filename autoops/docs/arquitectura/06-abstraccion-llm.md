# 06 — Abstracción de embeddings y LLM interno

Decisión marco (ADR-003): el código de AutoOps **no sabe qué proveedor** calcula los embeddings ni ejecuta el
LLM interno. La implementación por defecto es **Gemini (Google AI Studio, capa gratuita)**.

## 1. Dónde usa AutoOps un modelo (y dónde no)

AutoOps es un servidor MCP: **el razonamiento de cara al usuario lo hace el cliente** (Claude Code/Copilot).
El servidor solo usa modelos en dos puntos **internos**:

| Uso | Interfaz LangChain4j | Por qué |
|---|---|---|
| **Embeddings** del corpus y de las consultas | `EmbeddingModel` | Indexar y buscar en Weaviate. |
| **LLM interno** del bucle CRAG (`rewrite`) | `ChatModel` | Reformular/expandir la consulta cuando la recuperación es floja. |
| **LLM interno** de la redacción del chat | `ChatModel` | Redactar la respuesta del front de búsqueda (no la tool MCP). |

La tool MCP `buscar_docs` **no redacta**: devuelve chunks. Por eso el LLM del servidor es pequeño y de uso
acotado; no es el cerebro del producto.

La regla del proyecto: **ninguna clase fuera de `io.github.mikel0ne.autoops.llm.provider` importa clases de un proveedor
concreto.**

| Interfaz (la que ven RAG y chat) | Implementación Gemini (solo en `llm/provider`) |
|---|---|
| `dev.langchain4j.model.embedding.EmbeddingModel` | `GoogleAiEmbeddingModel` (`gemini-embedding-001`) |
| `dev.langchain4j.model.chat.ChatModel` | `GoogleAiGeminiChatModel` (`gemini-2.5-flash-lite`) |

> El modelo de embeddings real es **`gemini-embedding-001`**, no `text-embedding-004`: este último **no está
> disponible en el free tier** de Google AI Studio (lección de despliegue real, ver guía de infraestructura
> `03-weaviate.md`). La dimensión del vector la fija el modelo configurado, no un valor fijo en el código.

## 2. Configuración tipada

```java
package io.github.mikel0ne.autoops.config;

@Validated
@ConfigurationProperties("autoops.llm")
public record LlmProperties(
        @NotBlank String provider,            // gemini | (futuros: azure-openai, ollama...)
        @NotBlank String chatModelName,
        @NotBlank String embeddingModelName,
        @DurationUnit(ChronoUnit.SECONDS) Duration timeout,
        Double temperature
) {}
```

`application.yml`:

```yaml
autoops:
  llm:
    provider: gemini
    chat-model-name: gemini-2.5-flash-lite     # mayor cuota gratuita; uso interno
    embedding-model-name: gemini-embedding-001 # free tier real (text-embedding-004 no está disponible)
    timeout: 30s
    temperature: 0.2                            # bajo: reformular/redactar, no creatividad
```

La API key **no** va en YAML: variable de entorno `GEMINI_API_KEY` (en K8s, desde el Secret `autoops-llm-secret`).

## 3. Implementación por proveedor: un `@Configuration` condicional

```java
package io.github.mikel0ne.autoops.llm.provider;

@Configuration
@ConditionalOnProperty(name = "autoops.llm.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiModelConfig {

    @Bean
    public EmbeddingModel embeddingModel(LlmProperties props,
                                         @Value("${GEMINI_API_KEY}") String apiKey) {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(props.embeddingModelName())
                .build();
    }

    @Bean
    public ChatModel chatModel(LlmProperties props,
                               @Value("${GEMINI_API_KEY}") String apiKey) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(props.chatModelName())
                .temperature(props.temperature())
                .timeout(props.timeout())
                .build();
    }
}
```

## 4. Añadir un proveedor nuevo (receta)

Ejemplo: Azure OpenAI. Total: 1 dependencia + 1 clase + configuración. Cero cambios en tools, RAG, CRAG o K8s.

1. **Dependencia** en `pom.xml`: `langchain4j-azure-open-ai`.
2. **Clase** `AzureOpenAiModelConfig` junto a la de Gemini, con `@ConditionalOnProperty(... havingValue =
   "azure-openai")`, definiendo `EmbeddingModel` y `ChatModel`.
3. **Configuración** (despliegue): `AUTOOPS_LLM_PROVIDER=azure-openai`, claves en el Secret y model names en el
   YAML.
4. **Atención a los embeddings**: si el modelo de embeddings cambia de dimensión (la de `gemini-embedding-001`
   no es la misma que la de `text-embedding-3-small`, p. ej.), hay que **recrear la clase `DocChunk` de
   Weaviate** con la nueva dimensión y **re-ingestar** el corpus. El LLM interno, en cambio, es intercambiable
   sin migración.

## 5. El proveedor falso para tests

La misma mecánica da el "proveedor de tests": un `EmbeddingModel`/`ChatModel` stub, construido a mano (sin
`@Configuration` condicional). Permite probar el bucle CRAG programando respuestas fijas del reformulador y
scores controlados, sin tocar Gemini ni la red.

```java
class ScriptedChatModel implements ChatModel {
    private final Deque<String> responses;
    ScriptedChatModel(String... responses) { this.responses = new ArrayDeque<>(List.of(responses)); }
    @Override public ChatResponse chat(ChatRequest request) {
        return ChatResponse.builder().aiMessage(AiMessage.from(responses.poll())).build();
    }
}
```

## 6. Reglas de oro

1. Ningún `import dev.langchain4j.model.googleai.*` (ni de otro vendor) fuera de `llm/provider`.
2. Modelo, temperatura y timeout se leen siempre de `LlmProperties` — nunca hardcodeados.
3. Toda llamada al LLM lleva timeout.
4. Los logs nunca incluyen la API key ni payloads completos en niveles ≥ INFO.
5. La cuota gratuita de Gemini se respeta con: modelo `flash-lite`, temperatura baja y el uso **acotado** del
   LLM (solo `rewrite` del CRAG y redacción del chat; la tool MCP no consume LLM del servidor).
