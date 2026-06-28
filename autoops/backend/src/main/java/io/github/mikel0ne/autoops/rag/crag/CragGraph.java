package io.github.mikel0ne.autoops.rag.crag;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.nodes.ExpandNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.GradeNode;
import io.github.mikel0ne.autoops.rag.crag.nodes.MultiQueryNode;
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
 * Construye el grafo del RAG correctivo (CRAG) con LangGraph4j: {@code multiQuery → rerank → grade} y, si la
 * recuperación es floja, {@code grade → rewrite → retrieve → rerank → grade} hasta que el LLM evalúe los
 * resultados como suficientes o se agoten las iteraciones (doc 03 §3.3). Cuando {@code grade} da el visto
 * bueno, {@code expand} amplía a documento completo los fragmentos relevantes pero cortos antes de terminar.
 *
 * <p>La primera vuelta la hace {@code multiQuery} (abanico de variantes + RRF, doc 03 §3.2bis); los
 * reintentos siguen el camino antiguo, {@code retrieve} con una única consulta reformulada por
 * {@code rewrite} — el abanico no se reabre en cada reintento, para no multiplicar el coste en llamadas.
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
        ChatModel llm = chatModel.getObject();
        GradeNode grade = new GradeNode(llm, prompts.load("crag_grade.txt"));
        RewriteNode rewrite = new RewriteNode(
                llm, prompts.load("crag_rewrite.txt"), props.kStep(), props.kMax());
        MultiQueryNode multiQuery = new MultiQueryNode(
                retriever, llm, prompts.load("crag_multiquery.txt"), props.multiQueryN());
        try {
            return new StateGraph<>(CragState::new)
                    .addNode("multiQuery", node_async(multiQuery))
                    .addNode("retrieve", node_async(new RetrieveNode(retriever)))
                    .addNode("rerank", node_async(new RerankNode()))
                    .addNode("grade", node_async(grade))
                    .addNode("expand", node_async(new ExpandNode(retriever)))
                    .addNode("rewrite", node_async(rewrite))

                    .addEdge(START, "multiQuery")
                    .addEdge("multiQuery", "rerank")
                    .addEdge("retrieve", "rerank")
                    .addEdge("rerank", "grade")

                    .addConditionalEdges("grade",
                            edge_async(s -> {
                                if (s.suficiente()) return "ok";                       // recuperación buena
                                if (s.iteraciones() >= props.maxIteraciones()) return "ok"; // tope: lo mejor obtenido
                                return "retry";                                        // amplía y reintenta
                            }),
                            EdgeMappings.builder()
                                    .to("expand", "ok")
                                    .to("rewrite", "retry")
                                    .build())

                    .addEdge("expand", END)
                    .addEdge("rewrite", "retrieve")
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("No se pudo compilar el grafo CRAG", e);
        }
    }
}
