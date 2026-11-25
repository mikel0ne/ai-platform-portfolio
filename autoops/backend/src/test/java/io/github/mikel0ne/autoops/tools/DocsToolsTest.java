package io.github.mikel0ne.autoops.tools;

import io.github.mikel0ne.autoops.chat.SearchRequest;
import io.github.mikel0ne.autoops.error.InvalidRequestException;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTestTracing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocsToolsTest {

    @Test
    void rejects_a_query_over_the_limit_before_reaching_the_rag() {
        DocsTools tools = new DocsTools(null, LangfuseTestTracing.noop());

        assertThatThrownBy(() -> tools.searchDocs("a".repeat(SearchRequest.MAX_QUERY_LENGTH + 1), null))
                .isInstanceOf(InvalidRequestException.class);
    }
}
