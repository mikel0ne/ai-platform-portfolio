package io.github.mikel0ne.l1copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Development-only LLM response cache, off unless cache.enabled=true.
@ConfigurationProperties(prefix = "cache")
public record CacheProperties(@DefaultValue("false") boolean enabled,
                              @DefaultValue File file,
                              @DefaultValue("3600") long ttlSeconds) {

    public record File(@DefaultValue("true") boolean enabled, @DefaultValue("./cache") String path) {
    }

    public boolean isCacheEnabled() {
        return enabled;
    }
}
