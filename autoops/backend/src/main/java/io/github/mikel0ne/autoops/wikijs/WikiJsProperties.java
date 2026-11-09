package io.github.mikel0ne.autoops.wikijs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("autoops.wikijs")
public record WikiJsProperties(String url, String publicUrl, String locale) {
    public WikiJsProperties {
        if (locale == null || locale.isBlank()) locale = "es";
    }
}
