package io.github.mikel0ne.autoops.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("autoops.llm")
public record LlmProperties(
        @NotBlank String provider,
        @NotBlank String chatModelName,
        @NotBlank String embeddingModelName,
        Duration timeout,
        Integer maxRetries,
        Double temperature
) {
    public LlmProperties {
        if (timeout == null) timeout = Duration.ofSeconds(30);
        if (maxRetries == null) maxRetries = 2;
        if (temperature == null) temperature = 0.2;
    }
}
