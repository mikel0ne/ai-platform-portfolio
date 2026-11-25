package io.github.mikel0ne.l1copilot.observability;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class LangfuseNodeHook implements NodeHook.WrapCall<SupportState> {

    private final Tracer tracer;
    private final boolean captureContent;

    public LangfuseNodeHook(Tracer langfuseTracer, LangfuseProperties properties) {
        this.tracer = langfuseTracer;
        this.captureContent = properties.isCaptureContent();
    }

    @Override
    public CompletableFuture<Map<String, Object>> applyWrap(String nodeId, SupportState state,
                                                            RunnableConfig config,
                                                            AsyncNodeActionWithConfig<SupportState> action) {
        Span span = tracer.spanBuilder(nodeId)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, observationTypeOf(nodeId))
                .startSpan();
        // A ThreadLocal scope is enough because node_async runs the node on the calling thread.
        try (Scope ignored = span.makeCurrent()) {
            return action.apply(state, config).whenComplete((delta, error) -> {
                if (error != null) {
                    span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_ERROR);
                    span.recordException(error);
                } else if (captureContent) {
                    span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, String.valueOf(delta));
                }
                span.end();
            });
        }
    }

    private static String observationTypeOf(String nodeId) {
        return switch (nodeId) {
            case "resolve_order", "tracking", "cancel_check", "cancel_exec",
                 "return_check", "return_exec", "tickets" -> LangfuseAttributes.TYPE_TOOL;
            case "faq" -> LangfuseAttributes.TYPE_RETRIEVER;
            default -> LangfuseAttributes.TYPE_SPAN;
        };
    }
}
