package io.github.mikel0ne.autoops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("autoops.http")
public record HttpClientProperties(@DefaultValue("5s") Duration connectTimeout,
                                   @DefaultValue("30s") Duration readTimeout) {
}
