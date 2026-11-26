package io.github.mikel0ne.l1copilot.observability;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Verdict;
import io.github.mikel0ne.l1copilot.cache.CachingChatModel;
import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import io.github.mikel0ne.l1copilot.config.CacheProperties;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

class LangfuseTurnSpanTreeTest {

    private static final String NO_PARENT = "0000000000000000";

    private LangfuseTestTracer tracing;
    private LangfuseTurnTracer turnTracer;

    @BeforeEach
    void setUp() {
        tracing = new LangfuseTestTracer();
        turnTracer = new LangfuseTurnTracer(tracing.tracer(),
                LangfuseTestTracer.properties(true), LangfuseTestTracer.disabledScoreGateway());
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    @DisplayName("Un turno produce chat.turn, router y gemini.chat anidados, con metadatos en la raiz")
    void turnProducesNestedSpanTree() throws Exception {
        ChatModel model = new FakeChatModel(
                new LangfuseChatModelListener(tracing.tracer(), "determinista", true));
        CompiledGraph<SupportState> graph = graphCalling(model);

        SupportState finalState;
        try (LangfuseTurnTracer.TurnTrace trace =
                     turnTracer.start("u1", "conv-42", "donde esta mi pedido")) {
            finalState = graph.invoke(turnInput(), RunnableConfig.builder().threadId("conv-42").build())
                    .orElseThrow();
            trace.complete(finalState, "tu pedido va en camino", 2, 0);
        }

        SpanData root = tracing.spanNamed(LangfuseTurnTracer.SPAN_NAME);
        SpanData node = tracing.spanNamed("router");
        SpanData generation = tracing.spanNamed("gemini.chat");

        assertThat(root.getParentSpanId()).isEqualTo(NO_PARENT);
        assertThat(node.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(generation.getParentSpanId()).isEqualTo(node.getSpanId());
        assertThat(generation.getTraceId()).isEqualTo(root.getTraceId());

        assertThat(attribute(root, LangfuseAttributes.OBSERVATION_TYPE))
                .isEqualTo(LangfuseAttributes.TYPE_AGENT);
        assertThat(attribute(root, LangfuseAttributes.SESSION_ID)).isEqualTo("conv-42");
        assertThat(attribute(root, LangfuseAttributes.USER_ID)).isEqualTo("u1");
        assertThat(attribute(root, LangfuseAttributes.ENVIRONMENT)).isEqualTo("test");
        assertThat(attribute(root, LangfuseAttributes.OBSERVATION_INPUT))
                .isEqualTo("donde esta mi pedido");
        assertThat(attribute(root, LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo("tu pedido va en camino");
        assertThat(root.getAttributes().get(LangfuseAttributes.TRACE_TAGS))
                .containsExactly("l1-copilot", "agent", Intent.TRACKING.name());

        assertThat(attribute(root, LangfuseAttributes.TRACE_METADATA_PREFIX + "intent"))
                .isEqualTo(Intent.TRACKING.name());
        assertThat(attribute(root, LangfuseAttributes.TRACE_METADATA_PREFIX + "verdict"))
                .isEqualTo(Verdict.NONE.name());
        assertThat(attribute(root, LangfuseAttributes.TRACE_METADATA_PREFIX + "orderId"))
                .isEqualTo("777");
        assertThat(attribute(root, LangfuseAttributes.TRACE_METADATA_PREFIX + "ordersShown"))
                .isEqualTo("2");
        assertThat(attribute(root, LangfuseAttributes.TRACE_METADATA_PREFIX + "resolved"))
                .isEqualTo("true");

        assertThat(attribute(node, LangfuseAttributes.OBSERVATION_TYPE))
                .isEqualTo(LangfuseAttributes.TYPE_SPAN);
    }

    @Test
    @DisplayName("Los nodos que consultan el dominio salen como tool y la FAQ como retriever")
    void nodeObservationTypesMatchTheirRole() throws Exception {
        LangfuseNodeHook hook =
                new LangfuseNodeHook(tracing.tracer(), LangfuseTestTracer.properties(true));

        CompiledGraph<SupportState> graph = new StateGraph<>(SupportState.SCHEMA, SupportState::new)
                .addWrapCallNodeHook(hook)
                .addNode("tracking", node_async(state -> Map.of()))
                .addNode("faq", node_async(state -> Map.of()))
                .addEdge(START, "tracking")
                .addEdge("tracking", "faq")
                .addEdge("faq", END)
                .compile(CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        graph.invoke(turnInput(), RunnableConfig.builder().threadId("conv-1").build());

        assertThat(attribute(tracing.spanNamed("tracking"), LangfuseAttributes.OBSERVATION_TYPE))
                .isEqualTo(LangfuseAttributes.TYPE_TOOL);
        assertThat(attribute(tracing.spanNamed("faq"), LangfuseAttributes.OBSERVATION_TYPE))
                .isEqualTo(LangfuseAttributes.TYPE_RETRIEVER);
    }

    @Test
    @DisplayName("Un acierto de cache no parece una llamada real: sale como cache=HIT y sin tokens")
    void cacheHitDoesNotLookLikeARealCall(@TempDir Path tmp) {
        LangfuseChatModelListener listener =
                new LangfuseChatModelListener(tracing.tracer(), "determinista", true);
        FileCacheManager cache = new FileCacheManager(
                new CacheProperties(true, new CacheProperties.File(false, tmp.toString()), 3600L));
        cache.init();
        ChatModel cached = new CachingChatModel(new FakeChatModel(listener), cache,
                tracing.tracer(), "determinista", true);

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("donde esta mi pedido"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gemini-3.1-flash-lite").temperature(0.0).build())
                .build();

        cached.chat(request);
        cached.chat(request);

        List<String> spanNames = tracing.finishedSpans().stream().map(SpanData::getName).toList();
        assertThat(spanNames).containsExactly("gemini.chat", "gemini.chat (cache)");

        SpanData hit = tracing.spanNamed("gemini.chat (cache)");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_TYPE))
                .isEqualTo(LangfuseAttributes.TYPE_GENERATION);
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache"))
                .isEqualTo("HIT");
        assertThat(attribute(hit, LangfuseAttributes.USAGE_DETAILS))
                .isEqualTo("{\"input\":0,\"output\":0,\"total\":0}");
        assertThat(attribute(hit, LangfuseAttributes.COST_DETAILS)).isEqualTo("{\"total\":0}");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo(FakeChatModel.REPLY);
    }

    private CompiledGraph<SupportState> graphCalling(ChatModel model) throws GraphStateException {
        LangfuseNodeHook hook =
                new LangfuseNodeHook(tracing.tracer(), LangfuseTestTracer.properties(true));
        return new StateGraph<>(SupportState.SCHEMA, SupportState::new)
                .addWrapCallNodeHook(hook)
                .addNode("router", node_async(state -> {
                    model.chat(state.userMessage());
                    return Map.of("intent", Intent.TRACKING.name(), "orderId", 777L, "resolved", true);
                }))
                .addEdge(START, "router")
                .addEdge("router", END)
                .compile(CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
    }

    private static Map<String, Object> turnInput() {
        return Map.of("userId", "u1", "userMessage", "donde esta mi pedido");
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}
