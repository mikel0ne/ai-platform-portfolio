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
import io.github.mikel0ne.l1copilot.observability.LangfuseAttributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Set;

public class CachingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CachingChatModel.class);

    private static final String HIT_SPAN_NAME = "gemini.chat (cache)";
    private static final String NO_USAGE = "{\"input\":0,\"output\":0,\"total\":0}";
    private static final String NO_COST = "{\"total\":0}";

    private final ChatModel delegate;
    private final FileCacheManager cache;
    private final Tracer tracer;
    private final String role;
    private final boolean captureContent;

    public CachingChatModel(ChatModel delegate, FileCacheManager cache,
                            Tracer tracer, String role, boolean captureContent) {
        this.delegate = delegate;
        this.cache = cache;
        this.tracer = tracer;
        this.role = role;
        this.captureContent = captureContent;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            return delegate.chat(request);
        }

        String key = buildKey(request);
        String text = cache.get(key);
        if (text != null) {
            log.debug("Cache HIT (key={})", key);
            traceCacheHit(request, text);
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

    // Zero tokens and cost: a cache hit consumed no quota and must not inflate the spend.
    private void traceCacheHit(ChatRequest request, String text) {
        Span span = tracer.spanBuilder(HIT_SPAN_NAME)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_GENERATION)
                .setAttribute(LangfuseAttributes.MODEL_NAME, String.valueOf(request.modelName()))
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role", role)
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache", "HIT")
                .setAttribute(LangfuseAttributes.USAGE_DETAILS, NO_USAGE)
                .setAttribute(LangfuseAttributes.COST_DETAILS, NO_COST)
                .startSpan();
        if (captureContent) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(request.messages()));
            span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, text);
        }
        span.end();
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
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
