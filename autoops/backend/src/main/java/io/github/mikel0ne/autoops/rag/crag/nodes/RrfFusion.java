package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RrfFusion {

    private static final int RRF_K = 60;

    private RrfFusion() {
    }

    static List<DocChunk> merge(List<List<DocChunk>> lists) {
        Map<String, Double> rrfScore = new LinkedHashMap<>();
        Map<String, DocChunk> representativeByKey = new LinkedHashMap<>();
        for (List<DocChunk> list : lists) {
            for (int range = 0; range < list.size(); range++) {
                DocChunk candidate = list.get(range);
                String sectionKey = sectionKey(candidate);
                rrfScore.merge(sectionKey, 1.0 / (RRF_K + range + 1), Double::sum);
                representativeByKey.merge(sectionKey, candidate,
                        (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> representativeByKey.get(entry.getKey()))
                .toList();
    }

    private static String sectionKey(DocChunk c) {
        return c.source() + "|" + c.section() + "|" + c.text();
    }
}
