package io.github.mikel0ne.autoops.rag.crag;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.llm.Grader;
import io.github.mikel0ne.autoops.rag.crag.llm.QueryVariantGenerator;
import io.github.mikel0ne.autoops.rag.crag.nodes.ExpandNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.GradeNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.MultiQueryNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.RetrieveNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.RewriteNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.TopKNode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
public class CragGraph {

    private final DocRetriever retriever;
    private final ObjectProvider<ChatModel> chatModel;
    private final CragProperties props;
    private final PromptLoader prompts;

    // Compiled lazily so the ChatModel (and its API key) is not needed at startup.
    private volatile CompiledGraph<CragState> compiled;

    public CragGraph(DocRetriever retriever,
                     ObjectProvider<ChatModel> chatModel,
                     CragProperties props,
                     PromptLoader prompts) {
        this.retriever = retriever;
        this.chatModel = chatModel;
        this.props = props;
        this.prompts = prompts;
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
        GradeNode grade = new GradeNode(AiServices.create(Grader.class, llm), props.maxIterations());
        RewriteNode rewrite = new RewriteNode(
                llm, prompts.load("crag_rewrite.txt"), props.kStep(), props.kMax());
        MultiQueryNode multiQuery = new MultiQueryNode(
                retriever, AiServices.create(QueryVariantGenerator.class, llm), props.multiQueryN());
        try {
            return new StateGraph<>(CragState::new)
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
}
