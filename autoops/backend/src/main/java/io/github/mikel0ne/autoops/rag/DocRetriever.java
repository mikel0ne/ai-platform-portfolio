package io.github.mikel0ne.autoops.rag;

import java.util.List;

public interface DocRetriever {

    List<DocChunk> retrieve(String query, int k);

    List<DocChunk> fullSection(String docId, String section);

    List<DocChunk> neighboursInSection(String docId, String section, int order, int radius);
}
