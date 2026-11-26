package io.github.mikel0ne.l1copilot.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// maxAttempts counts the first call. Rate-limit waits only happen for per-minute quotas, never for daily ones.
@ConfigurationProperties(prefix = "copilot.llm-retry")
public record LlmRetryProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("4") int maxAttempts,
                                 @DefaultValue("2s") Duration transientDelay,
                                 @DefaultValue("20s") Duration maxTransientDelay,
                                 @DefaultValue("70s") Duration rateLimitMaxWait) {
}
