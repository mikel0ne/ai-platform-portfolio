package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.service.output.OutputParsingException;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.llm.QueryVariantGenerator;
import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Slf4j
public class MultiQueryNode implements NodeAction<CragState> {

    private final DocRetriever retriever;
    private final QueryVariantGenerator generator;
    private final int n;
    private final Executor executor;

    public MultiQueryNode(DocRetriever retriever, QueryVariantGenerator generator, int n) {
        this(retriever, generator, n, Runnable::run);
    }

    public MultiQueryNode(DocRetriever retriever, QueryVariantGenerator generator, int n, Executor executor) {
        this.retriever = retriever;
        this.generator = generator;
        this.n = n;
        this.executor = Context.taskWrapping(executor);
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        String original = state.currentQuery();
        int k = state.k();
        // The fan-out runs on the first pass only; retries use a single rewritten query to bound LLM cost.
        if (n <= 1) {
            return Map.of("candidates", retriever.retrieve(original, k));
        }

        List<String> variants = new ArrayList<>();
        variants.add(original);
        variants.addAll(generateVariants(original, n - 1));

        List<List<DocChunk>> resultsByVariant = retrieveAll(variants, k);

        List<DocChunk> merged = RrfFusion.merge(resultsByVariant).stream().limit(k).toList();
        return Map.of("candidates", merged, "questions", variants);
    }

    // Futures are joined in variant order, so fusion sees the same input as a sequential loop and the first
    // failure by variant order surfaces with its original exception.
    private List<List<DocChunk>> retrieveAll(List<String> variants, int k) {
        List<CompletableFuture<List<DocChunk>>> futures = variants.stream()
                .map(v -> CompletableFuture.supplyAsync(() -> retriever.retrieve(v, k), executor))
                .toList();
        try {
            return futures.stream().map(CompletableFuture::join).toList();
        } catch (CompletionException e) {
            futures.forEach(f -> f.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private List<String> generateVariants(String query, int count) {
        List<String> generated;
        try {
            generated = generator.generate(query, count).variants();
        } catch (OutputParsingException e) {
            log.warn("Unparseable query variants, searching with the original query only: {}", e.getMessage());
            return List.of();
        }
        if (generated == null) {
            return List.of();
        }
        return generated.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isBlank() && !v.equalsIgnoreCase(query))
                .distinct()
                .limit(count)
                .toList();
    }
}
