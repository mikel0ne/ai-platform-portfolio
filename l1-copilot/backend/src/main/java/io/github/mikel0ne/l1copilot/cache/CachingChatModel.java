package io.github.mikel0ne.l1copilot.cache;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Decorador de {@link ChatModel} que cachea respuestas en {@link FileCacheManager}.
 * <p>
 * Toda la conversación del agente pasa por aquí: el router y el redactor son
 * AiServices conectados a este bean, así que dos turnos idénticos solo consumen
 * cuota la primera vez.
 * <p>
 * Si la petición incluye {@code toolSpecifications}, se baipasea la caché — el
 * resultado puede ser un {@code tool_call} dependiente del estado externo y
 * cachearlo abriría la puerta a ejecuciones repetidas no idempotentes. En este
 * proyecto el grafo no usa tool-calling del LLM (las herramientas son nodos de
 * LangGraph4j), así que en la práctica nunca se entra por esa rama.
 */
public class CachingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CachingChatModel.class);

    private final ChatModel delegate;
    private final FileCacheManager cache;

    public CachingChatModel(ChatModel delegate, FileCacheManager cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            return delegate.chat(request);
        }

        String key = buildKey(request);
        Object cached = cache.get(key);
        if (cached instanceof String text) {
            log.debug("Cache HIT (key={})", key);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }

        log.debug("Cache MISS (key={})", key);
        ChatResponse response = delegate.chat(request);
        AiMessage msg = response.aiMessage();
        if (msg != null && msg.text() != null && !msg.hasToolExecutionRequests()) {
            cache.put(key, msg.text());
        }
        return response;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    /**
     * Clave estable: incluye todos los mensajes (tipo + contenido) y los parámetros
     * que pueden cambiar la respuesta. Dos {@code ChatRequest} con misma intención
     * pero distinto temperature/topP/topK no comparten clave.
     */
    private String buildKey(ChatRequest request) {
        StringBuilder sb = new StringBuilder(1024);
        for (ChatMessage msg : request.messages()) {
            sb.append(msg.type()).append('|').append(msg).append('\n');
        }
        ChatRequestParameters params = request.parameters();
        if (params != null) {
            sb.append("model=").append(params.modelName()).append('|');
            sb.append("temp=").append(params.temperature()).append('|');
            sb.append("topP=").append(params.topP()).append('|');
            sb.append("topK=").append(params.topK()).append('|');
            sb.append("maxOut=").append(params.maxOutputTokens()).append('|');
            sb.append("respFormat=").append(params.responseFormat());
        }
        return sha256(sb.toString());
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            return "key_" + input.hashCode();
        }
    }
}
