package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DocChunkDedup {

    private DocChunkDedup() {
    }

    static List<DocChunk> merge(List<DocChunk> earlier, List<DocChunk> fresh) {
        Map<String, DocChunk> byKey = new LinkedHashMap<>();
        earlier.forEach(c -> byKey.put(c.contentKey(), c));
        fresh.forEach(c -> byKey.put(c.contentKey(), c));
        return List.copyOf(byKey.values());
    }
}
