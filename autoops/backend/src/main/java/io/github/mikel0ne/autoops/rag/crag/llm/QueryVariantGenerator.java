package io.github.mikel0ne.autoops.rag.crag.llm;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

public interface QueryVariantGenerator {

    @UserMessage(fromResource = "/prompts/crag_multiquery.txt")
    QueryVariants generate(@V("query") String query, @V("n") int n);

    record QueryVariants(List<String> variants) {
    }
}
