package io.github.mikel0ne.l1copilot.resilience;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Google AI Studio (Gemini and Gemma): 429 bodies name the exceeded quota and carry "Please retry in 34.2s".
@Component
public class GoogleAiFailureClassifier implements LlmFailureClassifier {

    private static final Set<Integer> TRANSIENT_STATUS = Set.of(500, 502, 503, 504, 529);
    private static final Pattern RETRY_IN = Pattern.compile("retry in ([0-9]+(?:\\.[0-9]+)?)s");

    @Override
    public Verdict classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            Verdict verdict = classifyOne(t);
            if (verdict != null) {
                return verdict;
            }
        }
        return Verdict.notRetryable("not a known transient failure");
    }

    private Verdict classifyOne(Throwable t) {
        if (t instanceof RateLimitException) {
            return classifyRateLimit(String.valueOf(t.getMessage()));
        }
        if (t instanceof InternalServerException) {
            return Verdict.transientFailure("server error or overload");
        }
        if (t instanceof TimeoutException) {
            return Verdict.transientFailure("request timed out");
        }
        if (t instanceof HttpException http) {
            if (http.statusCode() == 429) {
                return classifyRateLimit(String.valueOf(http.getMessage()));
            }
            if (TRANSIENT_STATUS.contains(http.statusCode())) {
                return Verdict.transientFailure("HTTP " + http.statusCode());
            }
        }
        return null;
    }

    // Only a per-minute quota recovers by itself within a minute; waiting on a daily one just burns the attempts.
    private Verdict classifyRateLimit(String message) {
        if (message.contains("PerDay")) {
            return Verdict.notRetryable("daily quota exhausted");
        }
        if (message.contains("PerMinute")) {
            return Verdict.rateLimitPerMinute(retryHint(message), "per-minute quota exceeded");
        }
        return Verdict.notRetryable("rate limited, quota window unknown");
    }

    private Duration retryHint(String message) {
        Matcher m = RETRY_IN.matcher(message);
        if (!m.find()) {
            return null;
        }
        return Duration.ofMillis((long) (Double.parseDouble(m.group(1)) * 1000));
    }
}
