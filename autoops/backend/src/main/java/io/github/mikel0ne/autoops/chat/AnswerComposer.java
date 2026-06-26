package io.github.mikel0ne.autoops.chat;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Redacta la respuesta del chat con el LLM interno a partir de los fragmentos del RAG, con instrucción
 * <b>anti-alucinación</b> (doc 03 §4): si los documentos no contienen la respuesta, debe decirlo. Es el
 * único punto con LLM de redacción server-side; la tool MCP {@code buscar_docs} no pasa por aquí.
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
        if (rag.chunks().isEmpty()) {
            return "No he encontrado documentación interna que responda a esa consulta.";
        }
        String contexto = rag.chunks().stream()
                .map(AnswerComposer::formatChunk)
                .collect(Collectors.joining("\n\n"));
        String prompt = prompts.load("chat_answer.txt")
                .replace("{{consulta}}", consulta)
                .replace("{{contexto}}", contexto);
        return chatModel.getObject().chat(prompt).trim();
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
