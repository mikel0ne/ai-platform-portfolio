package io.github.mikel0ne.autoops.chat;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Redacta la respuesta del chat con el LLM interno a partir de los fragmentos del RAG. Intenta responder
 * <b>siempre</b> que haya algo recuperado, matizando según {@link RagResult#confianza()}: sin aviso en
 * {@code alta}, con aviso de cobertura parcial o débil en {@code media}/{@code baja}, y en {@code nula}
 * usando los {@link RagResult#candidatosDescartados()} con un aviso explícito al principio y al final (doc
 * 03 §4). Solo se niega a responder cuando no hay absolutamente nada que ofrecer. Es el único punto con LLM
 * de redacción server-side; la tool MCP {@code buscar_docs} no pasa por aquí.
 */
@Component
public class AnswerComposer {

    private final ObjectProvider<ChatModel> chatModel;
    private final PromptLoader prompts;

    public AnswerComposer(ObjectProvider<ChatModel> chatModel, PromptLoader prompts) {
        this.chatModel = chatModel;
        this.prompts = prompts;
    }

    public String compose(String consulta, RagResult rag) {
        List<DocChunk> base = rag.chunks().isEmpty() ? rag.candidatosDescartados() : rag.chunks();
        if (base.isEmpty()) {
            return "No he encontrado documentación interna que responda a esa consulta.";
        }
        String contexto = base.stream().map(AnswerComposer::formatChunk).collect(Collectors.joining("\n\n"));
        String prompt = prompts.load("chat_answer.txt")
                .replace("{{aviso}}", aviso(rag.confianza()))
                .replace("{{consulta}}", consulta)
                .replace("{{contexto}}", contexto);
        return chatModel.getObject().chat(prompt).trim();
    }

    private static String aviso(String confianza) {
        return switch (confianza) {
            case RagResult.MEDIA -> """
                    - AVISO: la información recuperada solo cubre una parte de la pregunta. Responde con lo \
                    que sí está cubierto y señala explícitamente qué parte no has podido confirmar con la \
                    documentación.
                    """;
            case RagResult.BAJA -> """
                    - AVISO: la información recuperada es solo tangencial, no se ha confirmado como respuesta \
                    directa. Indica claramente, al PRINCIPIO y al FINAL de tu respuesta, que la información \
                    no se ha catalogado como buena y debe tomarse con cautela.
                    """;
            case RagResult.NULA -> """
                    - AVISO IMPORTANTE: estos fragmentos fueron evaluados como NO relevantes para esta \
                    pregunta. Respóndelos solo si la conexión es clara e indudable; si no la encuentras, \
                    dilo. Indica EXPLÍCITAMENTE al PRINCIPIO y al FINAL de tu respuesta que no se ha \
                    encontrado documentación que responda con garantías a esta consulta.
                    """;
            default -> "";
        };
    }

    private static String formatChunk(DocChunk c) {
        String titulo = c.titulo() == null || c.titulo().isBlank() ? c.fuente() : c.titulo();
        String cabecera = "[" + titulo + " · " + c.seccion() + "]";
        if (c.url() != null && !c.url().isBlank()) {
            cabecera += " (" + c.url() + ")";
        }
        return cabecera + "\n" + c.texto();
    }
}
