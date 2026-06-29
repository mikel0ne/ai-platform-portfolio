package io.github.mikel0ne.autoops.wikijs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Conexión a Wiki.js (wiki self-hosted, ver infra/INFRA.md §7), fuente adicional del RAG además del repo
 * Git. Sin {@code url} la fuente queda desactivada (mismo criterio que las demás fuentes opcionales).
 *
 * @param url       base interna para las llamadas GraphQL (en-cluster), p. ej.
 *                  {@code http://wikijs-generic-app.wiki.svc.cluster.local:3000}
 * @param publicUrl base navegable desde el browser del usuario, para construir las citas del RAG
 *                  (p. ej. {@code http://wiki.local}); si falta, se usa {@code url}
 * @param locale    locale de las páginas a ingestar
 */
@Validated
@ConfigurationProperties("autoops.wikijs")
public record WikiJsProperties(String url, String publicUrl, String locale) {

    public WikiJsProperties {
        if (locale == null || locale.isBlank()) locale = "es";
    }
}
