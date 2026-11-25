package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.error.TooManyRequestsException;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseAttributes;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTracing;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SearchController {

    private static final List<String> TAGS = List.of("autoops", "rag", "search");

    private final RagService ragService;
    private final AnswerComposer answerComposer;
    private final SearchRateLimiter rateLimiter;
    private final LangfuseTracing tracing;

    public SearchController(RagService ragService, AnswerComposer answerComposer, SearchRateLimiter rateLimiter,
                            LangfuseTracing tracing) {
        this.ragService = ragService;
        this.answerComposer = answerComposer;
        this.rateLimiter = rateLimiter;
        this.tracing = tracing;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        if (!rateLimiter.tryAcquire()) {
            throw new TooManyRequestsException("Too many searches, try again in a minute");
        }
        Span root = tracing.root("search", LangfuseAttributes.TYPE_CHAIN, request.query(), TAGS);
        try (Scope ignored = root.makeCurrent()) {
            tracing.traceMetadata(root, "front", "search");
            RagResult rag = ragService.search(request.query(), 0);

            String answer = answerComposer.compose(request.query(), rag);
            tracing.output(root, answer);
            return new SearchResponse(answer, groupBySection(rag.evidence()), rag.confidence());
        } catch (RuntimeException e) {
            tracing.fail(root, e);
            throw e;
        } finally {
            root.end();
        }
    }

    static List<DocChunk> groupBySection(List<DocChunk> chunks) {
        Map<String, DocChunk> byKey = new LinkedHashMap<>();
        for (DocChunk c : chunks) {
            byKey.merge(c.source() + " | " + c.section(), c, (a, b) -> b.score() > a.score() ? b : a);
        }
        return List.copyOf(byKey.values());
    }
}
