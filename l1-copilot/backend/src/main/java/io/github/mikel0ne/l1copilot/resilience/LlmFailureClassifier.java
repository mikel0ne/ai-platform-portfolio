package io.github.mikel0ne.l1copilot.resilience;

import java.time.Duration;

// Provider-specific knowledge lives behind this interface; RetryingChatModel knows nothing about any vendor.
public interface LlmFailureClassifier {

    Verdict classify(Throwable failure);

    enum Kind { TRANSIENT, RATE_LIMIT_MINUTE, NOT_RETRYABLE }

    // suggestedWait is null when the provider gave no hint.
    record Verdict(Kind kind, Duration suggestedWait, String reason) {

        public static Verdict notRetryable(String reason) {
            return new Verdict(Kind.NOT_RETRYABLE, null, reason);
        }

        public static Verdict transientFailure(String reason) {
            return new Verdict(Kind.TRANSIENT, null, reason);
        }

        public static Verdict rateLimitPerMinute(Duration suggestedWait, String reason) {
            return new Verdict(Kind.RATE_LIMIT_MINUTE, suggestedWait, reason);
        }

        public boolean retryable() {
            return kind != Kind.NOT_RETRYABLE;
        }
    }
}
