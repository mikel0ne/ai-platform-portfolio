package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExpandNode implements NodeAction<CragState> {

    // Auto-merging: merge a section when it is small or several of its cores hit, never above the ceiling.
    private static final int SMALL_SECTION_CHARS = 3000;

    private static final int MAX_MERGED_CHARS = 8000;

    private static final int NEIGHBOUR_RADIUS = 1;

    private final DocRetriever retriever;

    public ExpandNode(DocRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        if (!state.sufficient()) {
            return Map.of();
        }

        Map<String, List<DocChunk>> bySection = new LinkedHashMap<>();
        for (DocChunk c : state.candidates()) {
            bySection.computeIfAbsent(sectionKey(c), k -> new ArrayList<>()).add(c);
        }
        List<DocChunk> output = new ArrayList<>();
        for (List<DocChunk> group : bySection.values()) {
            output.addAll(expandGroup(group));
        }
        return Map.of("candidates", DocChunkDedup.merge(List.of(), output));
    }

    private List<DocChunk> expandGroup(List<DocChunk> group) {
        DocChunk ref = group.get(0);
        boolean noDocId = ref.docId() == null || ref.docId().isBlank();
        if (noDocId || ref.sectionChars() > MAX_MERGED_CHARS) {
            return group;
        }
        if (group.size() >= 2 || ref.sectionChars() <= SMALL_SECTION_CHARS) {
            return List.of(mergeSection(group));
        }
        return List.of(withNeighbours(ref));
    }

    private DocChunk mergeSection(List<DocChunk> group) {
        DocChunk ref = group.get(0);
        List<DocChunk> section = retriever.fullSection(ref.docId(), ref.section());
        if (section.isEmpty()) {
            return ref;
        }
        String text = section.stream().map(DocChunk::text).collect(Collectors.joining("\n\n"));
        double score = group.stream().mapToDouble(DocChunk::score).max().orElse(ref.score());
        return new DocChunk(text, ref.title(), ref.source(), ref.section(), ref.url(), ref.origin(),
                score, ref.docId(), ref.sectionChars(), ref.order());
    }

    private DocChunk withNeighbours(DocChunk hit) {
        List<DocChunk> neighbours = retriever.neighboursInSection(
                hit.docId(), hit.section(), hit.order(), NEIGHBOUR_RADIUS);
        if (neighbours.size() <= 1) {
            return hit;
        }
        String text = neighbours.stream().map(DocChunk::text).collect(Collectors.joining("\n\n"));
        return new DocChunk(text, hit.title(), hit.source(), hit.section(), hit.url(), hit.origin(),
                hit.score(), hit.docId(), hit.sectionChars(), hit.order());
    }

    private static String sectionKey(DocChunk c) {
        return c.docId() + " | " + c.section();
    }
}
