package io.github.mikel0ne.l1copilot.service;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Pasarela directa al LLM (endpoint {@code /api/llm/*}).
 * <p>
 * No gestiona caché: el {@code ChatModel} inyectado ya está envuelto por
 * {@code CachingChatModel}, así que todas las llamadas se cachean en el mismo
 * punto que las del agente.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final ChatModel chatModel;

    /** Envía un prompt al LLM (con caché por debajo). */
    public String chat(String prompt) {
        log.info("Enviando prompt al LLM: {}", prompt);
        return chatModel.chat(prompt);
    }

    /** Hola mundo de diagnóstico. */
    public String sayHello() {
        return chat("Di 'Hola mundo desde LangChain4j con Spring Boot' de forma breve");
    }
}
