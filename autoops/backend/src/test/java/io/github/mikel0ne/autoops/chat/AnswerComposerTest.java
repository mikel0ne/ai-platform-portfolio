package io.github.mikel0ne.autoops.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El redactor del chat intenta responder siempre que hay algo recuperado, matizando según la confianza
 * heredada del RAG (doc 03 §4): sin aviso en alta, con aviso en media/baja, con aviso doble y candidatos
 * descartados en nula, y solo se niega cuando no hay absolutamente nada que ofrecer. */
class AnswerComposerTest {

    private final PromptLoader prompts = new PromptLoader();

    private static DocChunk chunk(String texto, double score) {
        return new DocChunk(texto, "Título", "fuente.md", "seccion", "", "test", score, "doc", 0, 0);
    }

    /** ChatModel que devuelve un eco fijo y guarda el último prompt recibido, para inspeccionar el aviso. */
    private static final class CapturingChatModel implements ChatModel {
        String ultimoPrompt;

        @Override
        public ChatResponse chat(ChatRequest request) {
            ultimoPrompt = UserMessage.findLast(request.messages()).map(UserMessage::singleText).orElse("");
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
    void confianza_alta_no_lleva_aviso() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = new RagResult(List.of(chunk("contenido bueno", 0.9)), 0, RagResult.ALTA, List.of());

        String respuesta = composer.compose("¿qué probes hay?", rag);

        assertThat(respuesta).isEqualTo("respuesta redactada");
        assertThat(chat.ultimoPrompt).doesNotContain("AVISO");
    }

    @Test
    void confianza_media_avisa_de_cobertura_parcial() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = new RagResult(List.of(chunk("solo cubre una parte", 0.7)), 1, RagResult.MEDIA, List.of());

        composer.compose("pregunta compuesta", rag);

        assertThat(chat.ultimoPrompt).contains("AVISO").contains("cubre una parte");
    }

    @Test
    void confianza_baja_avisa_al_principio_y_al_final() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = new RagResult(List.of(chunk("tangencial", 0.4)), 2, RagResult.BAJA, List.of());

        composer.compose("pregunta dudosa", rag);

        assertThat(chat.ultimoPrompt).contains("AVISO").contains("PRINCIPIO").contains("FINAL");
    }

    @Test
    void confianza_nula_usa_los_candidatos_descartados_con_aviso_fuerte() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var descartado = chunk("evaluado como no relevante pero es lo unico que hay", 0.3);
        var rag = new RagResult(List.of(), 2, RagResult.NULA, List.of(descartado));

        String respuesta = composer.compose("consulta rara", rag);

        assertThat(respuesta).isEqualTo("respuesta redactada"); // intenta responder, no se niega
        assertThat(chat.ultimoPrompt)
                .contains("AVISO IMPORTANTE")
                .contains("PRINCIPIO").contains("FINAL")
                .contains("evaluado como no relevante");
    }

    @Test
    void sin_nada_en_absoluto_se_niega_sin_llamar_al_llm() {
        var chat = new CapturingChatModel();
        var composer = new AnswerComposer(provider(chat), prompts);
        var rag = RagResult.empty(0);

        String respuesta = composer.compose("consulta sin nada", rag);

        assertThat(respuesta).isEqualTo("No he encontrado documentación interna que responda a esa consulta.");
        assertThat(chat.ultimoPrompt).isNull(); // no se llamó al LLM
    }
}
