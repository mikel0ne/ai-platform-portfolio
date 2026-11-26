package io.github.mikel0ne.autoops.rag.crag;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.platform.commons.langfuse.LangfuseAttributes;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTracing;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.llm.Grader;
import io.github.mikel0ne.autoops.rag.crag.llm.QueryVariantGenerator;
import io.github.mikel0ne.autoops.rag.crag.nodes.ExpandNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.GradeNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.MultiQueryNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.RetrieveNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.RewriteNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.TopKNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
@RequiredArgsConstructor
public class CragGraph implements DisposableBean {

    private final DocRetriever retriever;
    private final ObjectProvider<ChatModel> chatModel;
    private final CragProperties props;
    private final PromptLoader prompts;
    private final LangfuseTracing tracing;

    // Compiled lazily so the ChatModel (and its API key) is not needed at startup.
    private volatile CompiledGraph<CragState> compiled;

    private volatile ExecutorService retrievalExecutor;

    @Override
    public void destroy() {
        ExecutorService executor = retrievalExecutor;
        if (executor != null) {
            executor.shutdown();
        }
    }

    public CompiledGraph<CragState> get() {
        CompiledGraph<CragState> local = compiled;
        if (local == null) {
            synchronized (this) {
                local = compiled;
                if (local == null) {
                    compiled = local = build();
                }
            }
        }
        return local;
    }

    private CompiledGraph<CragState> build() {
        ChatModel llm = chatModel.getObject();
        // Small on purpose: every variant embeds its query, and the embedding API has a per-minute quota.
        ExecutorService executor = Executors.newFixedThreadPool(props.retrievalParallelism(), new RetrievalThreadFactory());
        retrievalExecutor = executor;
        GradeNode grade = new GradeNode(AiServices.create(Grader.class, llm), props.maxIterations());
        RewriteNode rewrite = new RewriteNode(
                llm, prompts.load("crag_rewrite.txt"), props.kStep(), props.kMax());
        MultiQueryNode multiQuery = new MultiQueryNode(
                retriever, AiServices.create(QueryVariantGenerator.class, llm), props.multiQueryN(), executor);
        try {
            return new StateGraph<>(CragState::new)
                    .addWrapCallNodeHook(nodeSpanHook(tracing))
                    .addNode("multiQuery", node_async(multiQuery))
                    .addNode("retrieve", node_async(new RetrieveNode(retriever)))
                    .addNode("topK", node_async(new TopKNode()))
                    .addNode("grade", node_async(grade))
                    .addNode("expand", node_async(new ExpandNode(retriever)))
                    .addNode("rewrite", node_async(rewrite))

                    .addEdge(START, "multiQuery")
                    .addEdge("multiQuery", "topK")
                    .addEdge("retrieve", "topK")
                    .addEdge("topK", "grade")

                    // The iteration cap lives on the edge, which guarantees termination whatever the grader says.
                    .addConditionalEdges("grade",
                            edge_async(s -> {
                                if (s.sufficient()) return "ok";
                                if (s.iterations() >= props.maxIterations()) return "ok";
                                return "retry";
                            }),
                            EdgeMappings.builder()
                                    .to("expand", "ok")
                                    .to("rewrite", "retry")
                                    .build())

                    .addEdge("expand", END)
                    .addEdge("rewrite", "retrieve")
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Could not compile the CRAG graph", e);
        }
    }

    // A ThreadLocal scope is enough because node_async runs the node on the calling thread: that is what
    // hangs the ChatModelListener generation under the right node. Truly async nodes would break it.
    static NodeHook.WrapCall<CragState> nodeSpanHook(LangfuseTracing tracing) {
        return (nodeId, state, config, action) -> {
            Span span = tracing.child(nodeId, observationType(nodeId));
            try (Scope ignored = span.makeCurrent()) {
                return action.apply(state, config).whenComplete((delta, error) -> {
                    if (error != null) {
                        tracing.fail(span, error);
                    } else {
                        tracing.output(span, String.valueOf(delta));
                    }
                    span.end();
                });
            }
        };
    }

    private static String observationType(String nodeId) {
        return switch (nodeId) {
            case "multiQuery", "grade", "rewrite" -> LangfuseAttributes.TYPE_CHAIN;
            case "retrieve" -> LangfuseAttributes.TYPE_RETRIEVER;
            default -> LangfuseAttributes.TYPE_SPAN;
        };
    }

    private static final class RetrievalThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "crag-retrieval-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
