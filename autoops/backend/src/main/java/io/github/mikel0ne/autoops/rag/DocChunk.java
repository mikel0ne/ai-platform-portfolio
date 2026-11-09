package io.github.mikel0ne.autoops.rag;

import java.io.Serializable;

public record DocChunk(String text, String title, String source, String section, String url, String origin,
                       double score, String docId, int sectionChars, int order) implements Serializable {}
