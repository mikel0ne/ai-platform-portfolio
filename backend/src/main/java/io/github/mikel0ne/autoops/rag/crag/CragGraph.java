package io.github.mikel0ne.autoops.rag.crag;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.nodes.GradeNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.RerankNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.RetrieveNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.RewriteNode;
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

/**
 * Construye el grafo del RAG correctivo (CRAG) con LangGraph4j: {@code retrieve → rerank → grade} y, si la
 * recuperación es floja, {@code grade → rewrite → retrieve} hasta superar el umbral o agotar iteraciones
 * (doc 03 §3.3).
 *
 * <p>Puntos finos del diseño: el tope de iteraciones vive en el <b>edge</b> (garantiza terminación) y la
 * decisión es <b>pura</b> (solo lee estado), trivial de testear. El grafo se compila una vez de forma
 * perezosa, para no construir el {@code ChatModel} al arrancar el servidor.
 */
@Component
public class CragGraph {

    private final DocRetriever retriever;
    private final ObjectProvider<ChatModel> chatModel;
    private final CragProperties props;
    private final PromptLoader prompts;

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

    /** Grafo compilado (construcción perezosa con doble comprobación). */
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

    public CragProperties props() {
        return props;
    }

    private CompiledGraph<CragState> build() {
        RewriteNode rewrite = new RewriteNode(chatModel.getObject(), prompts.load("crag_rewrite.txt"));
        try {
            return new StateGraph<>(CragState::new)
                    .addNode("retrieve", node_async(new RetrieveNode(retriever)))
                    .addNode("rerank", node_async(new RerankNode()))
                    .addNode("grade", node_async(new GradeNode()))
                    .addNode("rewrite", node_async(rewrite))

                    .addEdge(START, "retrieve")
                    .addEdge("retrieve", "rerank")
                    .addEdge("rerank", "grade")

                    .addConditionalEdges("grade",
                            edge_async(s -> {
                                if (s.mejorScore() >= props.umbral()) return "ok";
                                if (s.iteraciones() >= props.maxIteraciones()) return "ok";
                                return "retry";
                            }),
                            EdgeMappings.builder()
                                    .toEND("ok")
                                    .to("rewrite", "retry")
                                    .build())

                    .addEdge("rewrite", "retrieve")
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("No se pudo compilar el grafo CRAG", e);
        }
    }
}
