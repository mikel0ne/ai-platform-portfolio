package io.github.mikel0ne.autoops.rag;

import java.io.Serializable;

// Serializable: LangGraph4j copies the CRAG state, chunks included, through Java serialization.
public record DocChunk(String text, String title, String source, String section, String url, String origin,
                       double score, String docId, int sectionChars, int order) implements Serializable {

    // DocChunk has no id of its own: the same text of the same section is the same chunk.
    public String contentKey() {
        return source + "|" + section + "|" + text;
    }
}
