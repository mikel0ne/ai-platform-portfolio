package io.github.mikel0ne.autoops.rag.crag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Dobles de prueba para el bucle CRAG: ni LLM real ni Weaviate ni clúster (doc 03 §5, doc 06 §5). */
final class TestSupport {

    private TestSupport() {}

    /** Recuperador con score programado por llamada; repite el último si se agotan. Cuenta invocaciones. */
    static final class FakeRetriever implements DocRetriever {
        private final double[] scoresPerCall;
        int calls = 0;

        FakeRetriever(double... scoresPerCall) {
            this.scoresPerCall = scoresPerCall;
        }

        @Override
        public List<DocChunk> retrieve(String consulta, int k) {
            double score = scoresPerCall[Math.min(calls, scoresPerCall.length - 1)];
            calls++;
            return List.of(new DocChunk("texto para: " + consulta, "fuente.md", "seccion", score));
        }
    }

    /** ChatModel con respuestas fijas (doc 06 §5): el reformulador del CRAG no toca la red. */
    static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> responses;

        ScriptedChatModel(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            String next = responses.isEmpty() ? "consulta reformulada" : responses.poll();
            return ChatResponse.builder().aiMessage(AiMessage.from(next)).build();
        }
    }

    /** ObjectProvider mínimo que siempre devuelve el mismo valor (para inyectar dobles sin contexto Spring). */
    static <T> ObjectProvider<T> provider(T value) {
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
}
