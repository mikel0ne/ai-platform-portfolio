package io.github.mikel0ne.autoops.chat;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("autoops.search")
public record SearchProperties(@DefaultValue("30") @Positive int maxRequestsPerMinute) {
}
