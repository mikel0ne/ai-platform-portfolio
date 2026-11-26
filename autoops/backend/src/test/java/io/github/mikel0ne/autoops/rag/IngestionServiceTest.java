package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.DocumentSource;
import io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource;
import io.github.mikel0ne.autoops.rag.source.SourceDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    private final WeaviateGateway gateway = mock(WeaviateGateway.class);

    private static DocumentSource source(String name, List<SourceDocument> docs) {
        return new DocumentSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<SourceDocument> read() {
                return docs;
            }
        };
    }

    private static SourceDocument doc(String id, String origin) {
        return new SourceDocument(id, "Title", id + ".md", origin, "", "# Title\n\nbody");
    }

    private IngestionService service(DocumentSource... sources) {
        return new IngestionService(gateway, new IngestionProperties(null, null, null), List.of(sources),
                mock(GitHubMarkdownSource.class));
    }

    @Test
    void each_source_is_ingested_within_its_own_origin() {
        when(gateway.ingest(anyList(), eq("local"), isNull())).thenReturn(1);
        when(gateway.ingest(anyList(), eq("wikijs"), isNull())).thenReturn(1);

        int n = service(source("local", List.of(doc("a", "local"))),
                source("wikijs", List.of(doc("b", "wikijs")))).ingestAll();

        assertThat(n).isEqualTo(2);
        verify(gateway).ingest(anyList(), eq("local"), isNull());
        verify(gateway).ingest(anyList(), eq("wikijs"), isNull());
    }

    @Test
    void an_empty_source_is_skipped_instead_of_wiping_its_documents() {
        service(source("local", List.of())).ingestAll();

        verify(gateway, never()).ingest(anyList(), any(), any());
    }

    @Test
    void nothing_is_written_when_a_later_source_fails_to_read() {
        DocumentSource failing = new DocumentSource() {
            @Override
            public String name() {
                return "wikijs";
            }

            @Override
            public List<SourceDocument> read() {
                throw new IllegalStateException("wiki down");
            }
        };

        assertThatThrownBy(() -> service(source("local", List.of(doc("a", "local"))), failing).ingestAll())
                .isInstanceOf(IllegalStateException.class);

        verify(gateway, never()).ingest(anyList(), any(), any());
    }
}
