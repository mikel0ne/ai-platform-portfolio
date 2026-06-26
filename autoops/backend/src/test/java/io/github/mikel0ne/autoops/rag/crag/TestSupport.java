package io.github.mikel0ne.autoops.rag.crag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Dobles de prueba para el bucle CRAG: ni LLM real ni Weaviate ni clúster (doc 03 §5, doc 06 §5). */
final class TestSupport {

    private TestSupport() {}

    /** Recuperador con score programado por llamada; repite el último si se agotan. Registra k recibido. */
    static final class FakeRetriever implements DocRetriever {
        private final double[] scoresPerCall;
        int calls = 0;
        final List<Integer> kRecibidos = new ArrayList<>();

        FakeRetriever(double... scoresPerCall) {
            this.scoresPerCall = scoresPerCall;
        }

        @Override
        public List<DocChunk> retrieve(String consulta, int k) {
            kRecibidos.add(k);
            double score = scoresPerCall[Math.min(calls, scoresPerCall.length - 1)];
            calls++;
            return List.of(new DocChunk("texto para: " + consulta, "Título", "fuente.md", "seccion", "", "test", score));
        }
    }

    /**
     * ChatModel con respuestas fijas, sin red (doc 06 §5). Lo consultan DOS nodos del grafo en el mismo
     * bean ({@code grade} y {@code rewrite}), así que distingue la petición por su contenido: el prompt de
     * {@code grade} (plantilla {@code crag_grade.txt}) termina en "Veredicto:"; el de {@code rewrite}
     * (plantilla {@code crag_rewrite.txt}) termina en "reformulada:". Cada cola tiene su propio valor por
     * defecto si se agota (prudente para grade: ambiguo = insuficiente).
     */
    static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> gradeRespuestas;
        private final Deque<String> rewriteRespuestas;

        ScriptedChatModel(List<String> gradeRespuestas, List<String> rewriteRespuestas) {
            this.gradeRespuestas = new ArrayDeque<>(gradeRespuestas);
            this.rewriteRespuestas = new ArrayDeque<>(rewriteRespuestas);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            String prompt = UserMessage.findLast(request.messages())
                    .map(UserMessage::singleText)
                    .orElse("");
            String next;
            if (prompt.contains("Veredicto:")) {
                next = gradeRespuestas.isEmpty() ? "INSUFICIENTE" : gradeRespuestas.poll();
            } else {
                next = rewriteRespuestas.isEmpty() ? "consulta reformulada" : rewriteRespuestas.poll();
            }
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
