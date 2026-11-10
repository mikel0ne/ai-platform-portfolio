package io.github.mikel0ne.autoops.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.Confidence;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerComposerTest {

    private final PromptLoader prompts = new PromptLoader();

    private static DocChunk chunk(String text, double score) {
        return new DocChunk(text, "Título", "fuente.md", "section", "", "test", score, "doc", 0, 0);
    }

    private static final class CapturingChatModel implements ChatModel {
        String lastPrompt;

        @Override
        public ChatResponse chat(ChatRequest request) {
            lastPrompt = UserMessage.findLast(request.messages()).map(UserMessage::singleText).orElse("");
            return ChatResponse.builder().aiMessage(AiMessage.from("respuesta redactada")).build();
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    @Test
    void high_confidence_has_no_warning() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = new RagResult(List.of(chunk("contenido bueno", 0.9)), 0, Confidence.HIGH, List.of());

        String answer = composer.compose("¿qué probes hay?", rag);

        assertThat(answer).isEqualTo("respuesta redactada");
        assertThat(chat.lastPrompt).doesNotContain("AVISO");
    }

    @Test
    void medium_confidence_warns_about_partial_coverage() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = new RagResult(List.of(chunk("solo cubre una parte", 0.7)), 1, Confidence.MEDIUM, List.of());

        composer.compose("pregunta compuesta", rag);

        assertThat(chat.lastPrompt).contains("AVISO").contains("cubre una parte");
    }

    @Test
    void low_confidence_warns_at_start_and_end() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = new RagResult(List.of(chunk("tangencial", 0.4)), 2, Confidence.LOW, List.of());

        composer.compose("pregunta dudosa", rag);

        assertThat(chat.lastPrompt).contains("AVISO").contains("PRINCIPIO").contains("FINAL");
    }

    @Test
    void no_confidence_uses_discarded_candidates_with_strong_warning() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var discardedChunk = chunk("evaluado como no relevante pero es lo unico que hay", 0.3);
        var rag = new RagResult(List.of(), 2, Confidence.NONE, List.of(discardedChunk));

        String answer = composer.compose("consulta rara", rag);

        assertThat(answer).isEqualTo("respuesta redactada");
        assertThat(chat.lastPrompt)
                .contains("AVISO IMPORTANTE")
                .contains("PRINCIPIO").contains("FINAL")
                .contains("evaluado como no relevante");
    }

    @Test
    void refuses_without_calling_the_llm_when_nothing_was_found() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = RagResult.empty(0);

        String answer = composer.compose("consulta sin nada", rag);

        assertThat(answer).isEqualTo("No he encontrado documentación interna que responda a esa consulta.");
        assertThat(chat.lastPrompt).isNull();
    }
}
