package io.github.mikel0ne.l1copilot.config;

import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Personaliza la spec OpenAPI para que los endpoints del Actuator
 * aparezcan al final en Swagger UI y los endpoints de caché justo antes.
 *
 * Estrategia: clasifica cada path por prefijo ({@code /actuator}, {@code /api/cache}) y
 * reconstruye la lista de tags del spec con el siguiente orden:
 *   1. Tags de controladores propios (alfabéticos).
 *   2. Tags del CacheController (alfabéticos).
 *   3. Tags del Actuator (alfabéticos).
 *
 * Swagger UI respeta el orden de los tags del spec siempre que no se
 * haya configurado {@code springdoc.swagger-ui.tags-sorter}.
 */
@Configuration
public class OpenApiConfig {

    private static final String ACTUATOR_PATH_PREFIX = "/actuator";
    private static final String CACHE_PATH_PREFIX    = "/api/cache";

    @Bean
    public OpenApiCustomizer actuatorTagsAtBottom() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            Set<String> appTags     = new LinkedHashSet<>();
            Set<String> cacheTags   = new LinkedHashSet<>();
            Set<String> actuatorTags = new LinkedHashSet<>();

            openApi.getPaths().forEach((path, pathItem) -> {
                boolean isActuator = path != null && path.startsWith(ACTUATOR_PATH_PREFIX);
                boolean isCache    = path != null && path.startsWith(CACHE_PATH_PREFIX);
                pathItem.readOperations().forEach(op -> {
                    if (op.getTags() == null) {
                        return;
                    }
                    if (isActuator) {
                        actuatorTags.addAll(op.getTags());
                    } else if (isCache) {
                        cacheTags.addAll(op.getTags());
                    } else {
                        appTags.addAll(op.getTags());
                    }
                });
            });

            // Conserva la metadata (description, externalDocs, etc.) de los tags ya declarados
            Map<String, Tag> existing = openApi.getTags() == null
                    ? Collections.emptyMap()
                    : openApi.getTags().stream()
                            .collect(Collectors.toMap(Tag::getName, Function.identity(), (a, b) -> a));

            List<Tag> ordered = new ArrayList<>();
            appTags.stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(name -> ordered.add(existing.getOrDefault(name, new Tag().name(name))));
            cacheTags.stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(name -> ordered.add(existing.getOrDefault(name, new Tag().name(name))));
            actuatorTags.stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(name -> ordered.add(existing.getOrDefault(name, new Tag().name(name))));

            openApi.setTags(ordered);
        };
    }
}
