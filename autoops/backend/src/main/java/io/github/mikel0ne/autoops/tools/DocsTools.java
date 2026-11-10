package io.github.mikel0ne.autoops.tools;

import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DocsTools {

    private final RagService ragService;

    public DocsTools(RagService ragService) {
        this.ragService = ragService;
    }

    // Returns raw chunks on purpose: the MCP client does the reasoning, unlike the /search chat.
    @Tool(name = "search_docs",
          description = "Searches the internal documentation and returns the most relevant chunks with their source, "
                  + "section and score. Use it for questions about internal standards, policies and guides. Also "
                  + "returns the corrective-loop iterations and a confidence level.")
    public RagResult searchDocs(
            @ToolParam(description = "Natural-language query") String query,
            @ToolParam(required = false, description = "Number of chunks to return (default 5, capped at autoops.crag.k-max)") Integer k) {
        return ragService.search(query, k == null ? 0 : k);
    }
}
