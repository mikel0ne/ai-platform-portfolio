package io.github.mikel0ne.autoops.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("autoops.ingestion")
public record IngestionProperties(String corpusPath, Integer maxChars, Integer overlapChars) {
    public IngestionProperties {
        if (corpusPath == null || corpusPath.isBlank()) corpusPath = "../corpus";
        if (maxChars == null) maxChars = 2000;
        if (overlapChars == null) overlapChars = 200;
    }
}
