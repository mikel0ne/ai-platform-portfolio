package io.github.mikel0ne.autoops.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mikel0ne.autoops.rag.Confidence;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import io.github.mikel0ne.autoops.rag.eval.RetrievalMetrics.GoldenCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Opt-in: needs an indexed Weaviate (port-forward), the reranker and GEMINI_API_KEY. See the autoops README.
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true")
class RagEvalTest {

    private static final int K = 5;
    private static final double DEFAULT_MIN_HIT_AT_K = 0.8;

    @Autowired
    private DocRetriever retriever;

    @Autowired
    private RagService ragService;

    @Test
    void retrieval_and_crag_meet_the_golden_set_thresholds() throws Exception {
        List<GoldenCase> cases;
        try (InputStream in = new ClassPathResource("rag-golden-set.json").getInputStream()) {
            cases = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }

        List<Integer> retrievalRanks = new ArrayList<>();
        List<Integer> cragRanks = new ArrayList<>();
        Map<Confidence, Integer> confidences = new EnumMap<>(Confidence.class);
        StringBuilder report = new StringBuilder("\n=== RAG eval (k=" + K + ") ===\n");
        for (GoldenCase c : cases) {
            int retrievalRank = RetrievalMetrics.rank(c, retriever.retrieve(c.query(), K));
            RagResult crag = ragService.search(c.query(), K);
            int cragRank = RetrievalMetrics.rank(c, crag.evidence());
            retrievalRanks.add(retrievalRank);
            cragRanks.add(cragRank);
            confidences.merge(crag.confidence(), 1, Integer::sum);
            report.append(String.format("  retrieval@%d crag@%d %-6s iter=%d | %s%n", retrievalRank, cragRank,
                    crag.confidence().code(), crag.iterations(), c.query()));
        }

        double hitAtK = RetrievalMetrics.hitRate(retrievalRanks, K);
        report.append(String.format("Hybrid + rerank: hit@1=%.2f hit@%d=%.2f MRR=%.2f%n",
                RetrievalMetrics.hitRate(retrievalRanks, 1), K, hitAtK,
                RetrievalMetrics.meanReciprocalRank(retrievalRanks)));
        report.append(String.format("CRAG evidence:   hit@%d=%.2f MRR=%.2f confidence=%s%n", K,
                RetrievalMetrics.hitRate(cragRanks, K), RetrievalMetrics.meanReciprocalRank(cragRanks), confidences));
        System.out.println(report);

        double min = Double.parseDouble(System.getenv().getOrDefault("RAG_EVAL_MIN_HIT",
                String.valueOf(DEFAULT_MIN_HIT_AT_K)));
        assertThat(hitAtK).as("hit@%d of hybrid search + rerank on the golden set", K).isGreaterThanOrEqualTo(min);
    }
}
