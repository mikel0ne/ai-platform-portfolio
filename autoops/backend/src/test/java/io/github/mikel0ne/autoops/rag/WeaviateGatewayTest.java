package io.github.mikel0ne.autoops.rag;

import dev.langchain4j.exception.RateLimitException;
import io.weaviate.client.v1.graphql.query.fields.Field;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WeaviateGatewayTest {

    @Test
    void reranks_on_the_indexed_text_property() {
        assertThat(WeaviateGateway.rerankExpression("probes"))
                .isEqualTo("rerank(property: \"" + WeaviateGateway.TEXT + "\" query: \"probes\") { score }");
    }

    @Test
    void escapes_quotes_and_flattens_line_breaks_in_the_rerank_query() {
        assertThat(WeaviateGateway.rerankExpression("say \"hi\"\nnow"))
                .contains("query: \"say \\\"hi\\\" now\"");
    }

    @Test
    void recognises_a_rate_limit_anywhere_in_the_cause_chain() {
        var wrapped = new IllegalStateException("embedding failed", new RateLimitException("429"));

        assertThat(WeaviateGateway.isRateLimited(wrapped)).isTrue();
        assertThat(WeaviateGateway.isRateLimited(new IllegalStateException("429 in a message"))).isFalse();
    }

    @Test
    void search_fields_request_the_text_and_the_scores() {
        Field[] fields = WeaviateGateway.searchFields("q");

        assertThat(Arrays.stream(fields).map(Field::getName))
                .contains(WeaviateGateway.TEXT, "_additional");
    }
}
