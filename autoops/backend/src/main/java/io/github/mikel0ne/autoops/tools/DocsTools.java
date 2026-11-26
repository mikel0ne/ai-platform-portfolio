package io.github.mikel0ne.autoops.tools;

import io.github.mikel0ne.autoops.chat.SearchRequest;
import io.github.mikel0ne.autoops.error.InvalidRequestException;
import io.github.mikel0ne.platform.commons.langfuse.LangfuseAttributes;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTracing;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DocsTools {

    private static final List<String> TAGS = List.of("autoops", "rag", "mcp-tool");

    private final RagService ragService;
    private final LangfuseTracing tracing;

    // Returns raw chunks on purpose: the MCP client does the reasoning, unlike the /search chat.
    @Tool(name = "search_docs",
          description = "Searches the internal documentation and returns the most relevant chunks with their source, "
                  + "section and score. Use it for questions about internal standards, policies and guides. Also "
                  + "returns the corrective-loop iterations and a confidence level.")
    public RagResult searchDocs(
            @ToolParam(description = "Natural-language query") String query,
            @ToolParam(required = false, description = "Number of chunks to return (default 5, capped at autoops.crag.k-max)") Integer k) {
        if (query != null && query.length() > SearchRequest.MAX_QUERY_LENGTH) {
            throw new InvalidRequestException("query must be at most " + SearchRequest.MAX_QUERY_LENGTH + " characters");
        }
        Span root = tracing.root("search_docs", LangfuseAttributes.TYPE_TOOL, query, TAGS);
        try (Scope ignored = root.makeCurrent()) {
            tracing.traceMetadata(root, "front", "mcp-tool");
            RagResult result = ragService.search(query, k == null ? 0 : k);
            tracing.output(root, summary(result));
            return result;
        } catch (RuntimeException e) {
            tracing.fail(root, e);
            throw e;
        } finally {
            root.end();
        }
    }

    // References and scores only, never the full text.
    private static String summary(RagResult result) {
        return result.evidence().stream()
                .map(c -> c.source() + " · " + c.section() + " (" + c.score() + ")")
                .collect(Collectors.joining("\n"));
    }
}
