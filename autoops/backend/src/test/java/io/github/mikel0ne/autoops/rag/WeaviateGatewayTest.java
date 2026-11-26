package io.github.mikel0ne.autoops.rag;

import dev.langchain4j.exception.RateLimitException;
import io.weaviate.client.v1.graphql.query.fields.Field;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void object_id_is_stable_per_document_position_so_reingestion_overwrites() {
        var first = new Chunk("v1", "doc", "T", "s.md", "S", "", "local", 3, "h1", 10);
        var edited = new Chunk("v2", "doc", "T", "s.md", "S", "", "local", 3, "h2", 10);
        var next = new Chunk("v1", "doc", "T", "s.md", "S", "", "local", 4, "h1", 10);

        assertThat(WeaviateGateway.objectId("C", first)).isEqualTo(WeaviateGateway.objectId("C", edited));
        assertThat(WeaviateGateway.objectId("C", first)).isNotEqualTo(WeaviateGateway.objectId("C", next));
        assertThat(WeaviateGateway.objectId("C", first)).isNotEqualTo(WeaviateGateway.objectId("Other", first));
    }

    @Test
    void search_fields_request_the_text_and_the_scores() {
        Field[] fields = WeaviateGateway.searchFields("q");

        assertThat(Arrays.stream(fields).map(Field::getName))
                .contains(WeaviateGateway.TEXT, "_additional");
    }

    private static Chunk chunk(String docId, int order) {
        return new Chunk("t", docId, "T", "s.md", "S", "", "local", order, "h", 1);
    }

    @SuppressWarnings("unchecked")
    private static WeaviateGateway gateway() {
        EmbeddingModel embedder = mock(EmbeddingModel.class);
        when(embedder.embedAll(anyList())).thenAnswer(inv -> Response.from(((List<TextSegment>) inv.getArgument(0))
                .stream().map(x -> Embedding.from(new float[]{1f})).toList()));
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(embedder);
        return spy(new WeaviateGateway(null, provider, provider, new WeaviateProperties(null, null, null, null),
                null, null));
    }

    @Test
    void counts_chunks_per_document_and_finds_vanished_documents() {
        var chunks = List.of(chunk("a", 0), chunk("a", 1), chunk("b", 0));

        assertThat(WeaviateGateway.chunkCounts(chunks)).containsExactly(Map.entry("a", 2), Map.entry("b", 1));
        assertThat(WeaviateGateway.staleDocIds(List.of("a", "gone", "b"), chunks)).containsExactly("gone");
    }

    @Test
    void ingestion_inserts_before_it_deletes_anything() {
        var gateway = gateway();
        doNothing().when(gateway).insert(anyList(), anyList());
        doNothing().when(gateway).deleteChunksFrom(anyString(), anyInt());
        doNothing().when(gateway).deleteDocument(anyString());
        doReturn(List.of("a", "gone")).when(gateway).existingDocIds("local", null);

        int n = gateway.ingest(List.of(chunk("a", 0), chunk("a", 1)), "local", null);

        assertThat(n).isEqualTo(2);
        InOrder order = inOrder(gateway);
        order.verify(gateway).insert(anyList(), anyList());
        order.verify(gateway).deleteChunksFrom("a", 2);
        order.verify(gateway).deleteDocument("gone");
        verify(gateway, never()).deleteDocument("a");
    }

    @Test
    void a_failed_insert_leaves_every_document_in_place() {
        var gateway = gateway();
        doThrow(new IllegalStateException("weaviate down")).when(gateway).insert(anyList(), anyList());

        assertThatThrownBy(() -> gateway.ingest(List.of(chunk("a", 0)), "local", null))
                .isInstanceOf(IllegalStateException.class);

        verify(gateway, never()).deleteChunksFrom(anyString(), anyInt());
        verify(gateway, never()).deleteDocument(anyString());
        verify(gateway, never()).existingDocIds(any(), any());
    }
}
