package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.error.TooManyRequestsException;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private final RagService ragService;
    private final AnswerComposer answerComposer;
    private final SearchRateLimiter rateLimiter;

    public SearchController(RagService ragService, AnswerComposer answerComposer, SearchRateLimiter rateLimiter) {
        this.ragService = ragService;
        this.answerComposer = answerComposer;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        if (!rateLimiter.tryAcquire()) {
            throw new TooManyRequestsException("Too many searches, try again in a minute");
        }
        RagResult rag = ragService.search(request.query(), 0);

        String answer = answerComposer.compose(request.query(), rag);
        List<DocChunk> base = rag.chunks().isEmpty() ? rag.discardedCandidates() : rag.chunks();
        return new SearchResponse(answer, groupBySection(base), rag.confidence());
    }

    static List<DocChunk> groupBySection(List<DocChunk> chunks) {
        Map<String, DocChunk> byKey = new LinkedHashMap<>();
        for (DocChunk c : chunks) {
            byKey.merge(c.source() + " | " + c.section(), c, (a, b) -> b.score() > a.score() ? b : a);
        }
        return List.copyOf(byKey.values());
    }
}
