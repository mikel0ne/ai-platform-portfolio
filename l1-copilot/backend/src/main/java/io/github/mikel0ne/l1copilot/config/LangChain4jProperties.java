package io.github.mikel0ne.l1copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "langchain4j.gemini")
public record LangChain4jProperties(
        String apiKey,
        String modelName,
        Duration timeout,
        Integer maxRetries,
        Temperatures temperatures,
        ThinkingLevels thinkingLevels) {
    public record Temperatures(Double deterministic, Double ticket, Double response, Double chat) {
        public Temperatures {
            if (deterministic == null) {
                deterministic = 0.0;
            }
            if (ticket == null) {
                ticket = 0.2;
            }
            if (response == null) {
                response = 0.35;
            }
            if (chat == null) {
                chat = 0.7;
            }
        }
    }

    public record ThinkingLevels(String deterministic, String ticket, String response, String chat) {
        public ThinkingLevels {
            if (deterministic == null) deterministic = "LOW";
            if (ticket == null)        ticket        = "NONE";
            if (response == null)      response      = "LOW";
            if (chat == null)          chat          = "NONE";
        }
    }

    public LangChain4jProperties {
        if (modelName == null || modelName.isBlank()) {
            modelName = "gemini-2.5-flash-lite";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        if (maxRetries == null) {
            maxRetries = 2;
        }
        if (temperatures == null) {
            temperatures = new Temperatures(null, null, null, null);
        }
        if (thinkingLevels == null) {
            thinkingLevels = new ThinkingLevels(null, null, null, null);
        }
    }
}
