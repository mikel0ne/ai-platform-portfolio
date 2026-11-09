package io.github.mikel0ne.autoops.rag;

public record Chunk(
        String text,
        String docId,
        String title,
        String source,
        String section,
        String url,
        String origin,
        int order,
        String hash,
        int sectionChars) {
}
