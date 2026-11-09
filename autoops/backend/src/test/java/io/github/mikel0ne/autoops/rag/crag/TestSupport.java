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

final class TestSupport {

    private TestSupport() {}

    static final class FakeRetriever implements DocRetriever {
        private final double[] scoresPerCall;
        int calls = 0;
        final List<Integer> receivedKs = new ArrayList<>();
        final List<String> receivedQueries = new ArrayList<>();

        FakeRetriever(double... scoresPerCall) {
            this.scoresPerCall = scoresPerCall;
        }

        @Override
        public List<DocChunk> retrieve(String query, int k) {
            receivedKs.add(k);
            receivedQueries.add(query);
            double score = scoresPerCall[Math.min(calls, scoresPerCall.length - 1)];
            calls++;
            return List.of(new DocChunk("texto para: " + query, "Título", "fuente.md", "section", "", "test", score, "", 0, 0));
        }

        @Override
        public List<DocChunk> fullSection(String docId, String section) {
            return List.of();
        }

        @Override
        public List<DocChunk> neighboursInSection(String docId, String section, int order, int radius) {
            return List.of();
        }
    }

    static final class MultiChunkRetriever implements DocRetriever {
        private final List<List<DocChunk>> chunksPerCall;
        private final Map<String, List<DocChunk>> sections;
        int calls = 0;
        int sectionCalls = 0;
        int neighbourCalls = 0;
        final List<Integer> receivedKs = new ArrayList<>();

        MultiChunkRetriever(List<List<DocChunk>> chunksPerCall) {
            this(chunksPerCall, Map.of());
        }

        MultiChunkRetriever(List<List<DocChunk>> chunksPerCall, Map<String, List<DocChunk>> sections) {
            this.chunksPerCall = chunksPerCall;
            this.sections = sections;
        }

        @Override
        public List<DocChunk> retrieve(String query, int k) {
            receivedKs.add(k);
            List<DocChunk> result = chunksPerCall.get(Math.min(calls, chunksPerCall.size() - 1));
            calls++;
            return result;
        }

        @Override
        public List<DocChunk> fullSection(String docId, String section) {
            sectionCalls++;
            return sections.getOrDefault(docId, List.of()).stream()
                    .filter(c -> c.section().equals(section))
                    .toList();
        }

        @Override
        public List<DocChunk> neighboursInSection(String docId, String section, int order, int radius) {
            neighbourCalls++;
            return sections.getOrDefault(docId, List.of()).stream()
                    .filter(c -> c.section().equals(section))
                    .filter(c -> Math.abs(c.order() - order) <= radius)
                    .toList();
        }
    }

    static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> gradeResponses;
        private final Deque<String> rewriteResponses;
        private final Deque<String> multiQueryResponses;

        ScriptedChatModel(List<String> gradeResponses, List<String> rewriteResponses) {
            this(gradeResponses, rewriteResponses, List.of());
        }

        ScriptedChatModel(List<String> gradeResponses, List<String> rewriteResponses,
                          List<String> multiQueryResponses) {
            this.gradeResponses = new ArrayDeque<>(gradeResponses);
            this.rewriteResponses = new ArrayDeque<>(rewriteResponses);
            this.multiQueryResponses = new ArrayDeque<>(multiQueryResponses);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            String prompt = UserMessage.findLast(request.messages())
                    .map(UserMessage::singleText)
                    .orElse("");
            String next;
            if (prompt.contains("Veredictos")) {
                next = gradeResponses.isEmpty() ? "1: INSUFICIENTE" : gradeResponses.poll();
            } else if (prompt.contains("Variantes")) {
                next = multiQueryResponses.isEmpty() ? "" : multiQueryResponses.poll();
            } else {
                next = rewriteResponses.isEmpty() ? "consulta reformulada" : rewriteResponses.poll();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(next)).build();
        }
    }

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
