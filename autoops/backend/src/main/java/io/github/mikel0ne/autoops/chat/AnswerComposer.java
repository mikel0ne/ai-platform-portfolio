package io.github.mikel0ne.autoops.chat;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AnswerComposer {

    private final ObjectProvider<ChatModel> chatModel;
    private final PromptLoader prompts;

    public AnswerComposer(ObjectProvider<ChatModel> chatModel, PromptLoader prompts) {
        this.chatModel = chatModel;
        this.prompts = prompts;
    }

    public String compose(String query, RagResult rag) {
        List<DocChunk> base = rag.chunks().isEmpty() ? rag.discardedCandidates() : rag.chunks();
        if (base.isEmpty()) {
            return "No he encontrado documentación interna que responda a esa consulta.";
        }
        String context = base.stream().map(AnswerComposer::formatChunk).collect(Collectors.joining("\n\n"));
        String prompt = prompts.load("chat_answer.txt")
                .replace("{{warning}}", warning(rag.confidence()))
                .replace("{{query}}", query)
                .replace("{{context}}", context);
        return chatModel.getObject().chat(prompt).trim();
    }

    private static String warning(String confidence) {
        return switch (confidence) {
            case RagResult.MEDIUM -> """
                    - AVISO: la información recuperada solo cubre una parte de la pregunta. Responde con lo \
                    que sí está cubierto y señala explícitamente qué parte no has podido confirmar con la \
                    documentación.
                    """;
            case RagResult.LOW -> """
                    - AVISO: la información recuperada es solo tangencial, no se ha confirmado como respuesta \
                    directa. Indica claramente, al PRINCIPIO y al FINAL de tu respuesta, que la información \
                    no se ha catalogado como buena y debe tomarse con cautela.
                    """;
            case RagResult.NONE -> """
                    - AVISO IMPORTANTE: estos fragmentos fueron evaluados como NO relevantes para esta \
                    pregunta. Respóndelos solo si la conexión es clara e indudable; si no la encuentras, \
                    dilo. Indica EXPLÍCITAMENTE al PRINCIPIO y al FINAL de tu respuesta que no se ha \
                    encontrado documentación que responda con garantías a esta consulta.
                    """;
            default -> "";
        };
    }

    private static String formatChunk(DocChunk c) {
        String title = c.title() == null || c.title().isBlank() ? c.source() : c.title();
        String header = "[" + title + " · " + c.section() + "]";
        if (c.url() != null && !c.url().isBlank()) {
            header += " (" + c.url() + ")";
        }
        return header + "\n" + c.text();
    }
}
