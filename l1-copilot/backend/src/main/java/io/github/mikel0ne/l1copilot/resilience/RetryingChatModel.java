package io.github.mikel0ne.l1copilot.resilience;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;

// Middleware around any ChatModel: every call of every role goes through the same retry policy.
public class RetryingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingChatModel.class);

    // A minute-quota hint is optimistic by a hair; this margin avoids landing just before the window resets.
    private static final Duration RATE_LIMIT_MARGIN = Duration.ofSeconds(2);
    private static final Duration DEFAULT_RATE_LIMIT_WAIT = Duration.ofSeconds(60);

    private final ChatModel delegate;
    private final LlmFailureClassifier classifier;
    private final LlmRetryProperties properties;
    private final Sleeper sleeper;
    private final String role;

    public RetryingChatModel(ChatModel delegate, LlmFailureClassifier classifier,
                             LlmRetryProperties properties, String role) {
        this(delegate, classifier, properties, role, RetryingChatModel::sleep);
    }

    RetryingChatModel(ChatModel delegate, LlmFailureClassifier classifier,
                      LlmRetryProperties properties, String role, Sleeper sleeper) {
        this.delegate = delegate;
        this.classifier = classifier;
        this.properties = properties;
        this.role = role;
        this.sleeper = sleeper;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int attempt = 1;
        while (true) {
            try {
                return delegate.chat(request);
            } catch (RuntimeException failure) {
                LlmFailureClassifier.Verdict verdict = classifier.classify(failure);
                Duration wait = verdict.retryable() ? waitFor(verdict, attempt) : null;
                if (wait == null || attempt >= properties.maxAttempts()) {
                    throw failure;
                }
                log.warn("LLM call [{}] failed ({}); retry {} of {} in {} s",
                        role, verdict.reason(), attempt, properties.maxAttempts() - 1, wait.toSeconds());
                sleeper.sleep(wait);
                attempt++;
            }
        }
    }

    // Returns null when the needed wait exceeds the cap: failing now beats holding the request thread.
    private Duration waitFor(LlmFailureClassifier.Verdict verdict, int attempt) {
        if (verdict.kind() == LlmFailureClassifier.Kind.RATE_LIMIT_MINUTE) {
            Duration hint = verdict.suggestedWait() != null ? verdict.suggestedWait() : DEFAULT_RATE_LIMIT_WAIT;
            Duration wait = hint.plus(RATE_LIMIT_MARGIN);
            return wait.compareTo(properties.rateLimitMaxWait()) <= 0 ? wait : null;
        }
        Duration exponential = properties.transientDelay().multipliedBy(1L << Math.min(attempt - 1, 10));
        return exponential.compareTo(properties.maxTransientDelay()) <= 0 ? exponential : properties.maxTransientDelay();
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

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry an LLM call", e);
        }
    }
}
