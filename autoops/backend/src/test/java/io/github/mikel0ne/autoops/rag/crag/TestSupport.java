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
import java.util.Map;

/** Dobles de prueba para el bucle CRAG: ni LLM real ni Weaviate ni clúster (doc 03 §5, doc 06 §5). */
final class TestSupport {

    private TestSupport() {}

    /** Recuperador con score programado por llamada; repite el último si se agotan. Registra k y consulta recibidos. */
    static final class FakeRetriever implements DocRetriever {
        private final double[] scoresPerCall;
        int calls = 0;
        final List<Integer> kRecibidos = new ArrayList<>();
        final List<String> consultasRecibidas = new ArrayList<>();

        FakeRetriever(double... scoresPerCall) {
            this.scoresPerCall = scoresPerCall;
        }

        @Override
        public List<DocChunk> retrieve(String consulta, int k) {
            kRecibidos.add(k);
            consultasRecibidas.add(consulta);
            double score = scoresPerCall[Math.min(calls, scoresPerCall.length - 1)];
            calls++;
            return List.of(new DocChunk("texto para: " + consulta, "Título", "fuente.md", "seccion", "", "test", score, ""));
        }

        @Override
        public List<DocChunk> documentoCompleto(String docId) {
            return List.of();
        }
    }

    /** Recuperador con lotes de chunks fijados por llamada (para lotes con relevancia mixta) y documentos
     * completos fijados por docId (para probar la expansión de {@code expand}). */
    static final class MultiChunkRetriever implements DocRetriever {
        private final List<List<DocChunk>> chunksPerCall;
        private final Map<String, List<DocChunk>> documentos;
        int calls = 0;
        final List<Integer> kRecibidos = new ArrayList<>();

        MultiChunkRetriever(List<List<DocChunk>> chunksPerCall) {
            this(chunksPerCall, Map.of());
        }

        MultiChunkRetriever(List<List<DocChunk>> chunksPerCall, Map<String, List<DocChunk>> documentos) {
            this.chunksPerCall = chunksPerCall;
            this.documentos = documentos;
        }

        @Override
        public List<DocChunk> retrieve(String consulta, int k) {
            kRecibidos.add(k);
            List<DocChunk> resultado = chunksPerCall.get(Math.min(calls, chunksPerCall.size() - 1));
            calls++;
            return resultado;
        }

        @Override
        public List<DocChunk> documentoCompleto(String docId) {
            return documentos.getOrDefault(docId, List.of());
        }
    }

    /**
     * ChatModel con respuestas fijas, sin red (doc 06 §5). Lo consultan TRES nodos del grafo en el mismo
     * bean ({@code grade}, {@code rewrite} y {@code multiQuery}), así que distingue la petición por su
     * contenido: el prompt de {@code grade} (plantilla {@code crag_grade.txt}) contiene "Veredictos"; el de
     * {@code multiQuery} (plantilla {@code crag_multiquery.txt}) contiene "Variantes"; el de {@code rewrite}
     * (plantilla {@code crag_rewrite.txt}, "reformulada:") es el resto. Cada cola tiene su propio valor por
     * defecto si se agota (prudente para grade: ambiguo = insuficiente).
     */
    static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> gradeRespuestas;
        private final Deque<String> rewriteRespuestas;
        private final Deque<String> multiQueryRespuestas;

        ScriptedChatModel(List<String> gradeRespuestas, List<String> rewriteRespuestas) {
            this(gradeRespuestas, rewriteRespuestas, List.of());
        }

        ScriptedChatModel(List<String> gradeRespuestas, List<String> rewriteRespuestas,
                          List<String> multiQueryRespuestas) {
            this.gradeRespuestas = new ArrayDeque<>(gradeRespuestas);
            this.rewriteRespuestas = new ArrayDeque<>(rewriteRespuestas);
            this.multiQueryRespuestas = new ArrayDeque<>(multiQueryRespuestas);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            String prompt = UserMessage.findLast(request.messages())
                    .map(UserMessage::singleText)
                    .orElse("");
            String next;
            if (prompt.contains("Veredictos")) {
                next = gradeRespuestas.isEmpty() ? "1: INSUFICIENTE" : gradeRespuestas.poll();
            } else if (prompt.contains("Variantes")) {
                next = multiQueryRespuestas.isEmpty() ? "" : multiQueryRespuestas.poll();
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
