package io.github.mikel0ne.autoops.rag.source;

public record SourceDocument(
        String docId,
        String title,
        String source,
        String origin,
        String baseUrl,
        String content) {
}
