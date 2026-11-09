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
        earlier.forEach(c -> byKey.put(sectionKey(c), c));
        fresh.forEach(c -> byKey.put(sectionKey(c), c));
        return List.copyOf(byKey.values());
    }

    private static String sectionKey(DocChunk c) {
        return c.source() + "" + c.section() + "" + c.text();
    }
}
