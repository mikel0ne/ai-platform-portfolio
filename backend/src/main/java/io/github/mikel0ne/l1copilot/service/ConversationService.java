package io.github.mikel0ne.l1copilot.service;

import dev.langchain4j.exception.LangChain4jException;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Puente entre la API REST y el grafo: un turno HTTP = una invocación del
 * grafo con {@code threadId} = id de conversación (ADR-2). El checkpointer
 * restaura el estado entre turnos, lo que permite los ciclos de clarificación.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final CompiledGraph<SupportState> supportAgent;

    public ChatResult chat(String userId, String conversationId, String message) {
        log.debug("Turno de conversación {} (usuario {})", conversationId, userId);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(conversationId)
                .build();

        // Valores por turno: el resto del estado (intent, orderId, awaiting,
        // candidates, ticketDraft) lo restaura el checkpointer.
        Map<String, Object> input = Map.of(
                "userId", userId,
                "userMessage", message,
                "history", List.of("USER: " + message),
                "confirmation", "NONE",
                "toolResult", "",
                "reply", "",
                "resolved", false);

        SupportState state;
        try {
            state = supportAgent.invoke(input, config)
                    .orElseThrow(() -> new ConversationException("El grafo no devolvió estado final"));
        } catch (ConversationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (causedByLlm(e)) {
                log.error("Fallo del modelo en la conversación {}", conversationId, e);
                throw new LlmUnavailableException(e);
            }
            log.error("Fallo procesando el turno de la conversación {}", conversationId, e);
            throw new ConversationException("Error procesando el mensaje", e);
        }

        log.debug("Turno completado: intent={} resolved={}", state.intent(), state.resolved());
        return new ChatResult(state.reply(), state.intent().name(), state.resolved());
    }

    /** El grafo envuelve las excepciones de los nodos: se inspecciona la cadena de causas. */
    private boolean causedByLlm(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof LangChain4jException) {
                return true;
            }
        }
        return false;
    }

    /** Resultado de un turno, listo para mapear al DTO de la API. */
    public record ChatResult(String reply, String intent, boolean resolved) {
    }

    /** El modelo (Gemini) no está disponible o agotó la cuota. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(Throwable cause) {
            super("El modelo de lenguaje no está disponible", cause);
        }
    }

    /** Error interno procesando el turno. */
    public static class ConversationException extends RuntimeException {
        public ConversationException(String message) {
            super(message);
        }

        public ConversationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
