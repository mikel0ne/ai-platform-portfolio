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
            for (int rank = 0; rank < list.size(); rank++) {
                DocChunk candidate = list.get(rank);
                String key = candidate.contentKey();
                rrfScore.merge(key, 1.0 / (RRF_K + rank + 1), Double::sum);
                representativeByKey.merge(key, candidate,
                        (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> representativeByKey.get(entry.getKey()))
                .toList();
    }
}
