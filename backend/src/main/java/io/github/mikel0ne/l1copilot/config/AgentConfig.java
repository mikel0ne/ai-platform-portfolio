package io.github.mikel0ne.l1copilot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.github.mikel0ne.l1copilot.agent.SupportAgentGraph;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.Responder;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter;
import io.github.mikel0ne.l1copilot.agent.llm.Verifier;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring del agente: AiServices (router y redactor), checkpointer y grafo compilado.
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public IntentClassifier intentClassifier(ChatModel chatModel) {
        return AiServices.create(IntentClassifier.class, chatModel);
    }

    @Bean
    public Responder responder(ChatModel chatModel) {
        return AiServices.create(Responder.class, chatModel);
    }

    @Bean
    public TicketWriter ticketWriter(ChatModel chatModel) {
        return AiServices.create(TicketWriter.class, chatModel);
    }

    @Bean
    public Verifier verifier(ChatModel chatModel) {
        return AiServices.create(Verifier.class, chatModel);
    }

    /**
     * Checkpointer del grafo: persiste el estado de cada conversación entre turnos HTTP.
     * <p>
     * Actualmente usa {@link MemorySaver} (RAM del proceso). Las conversaciones en curso
     * se pierden al reiniciar el contenedor — aceptable para este prototipo.
     * <p>
     * Para persistencia real habría que implementar {@link BaseCheckpointSaver} sobre
     * PostgreSQL via JDBC. LangGraph4j 1.8.x no incluye esa implementación out-of-the-box.
     */
    @Bean
    public BaseCheckpointSaver checkpointSaver() {
        return new MemorySaver();
    }

    @Bean
    public CompiledGraph<SupportState> supportAgent(SupportAgentGraph builder,
                                                    BaseCheckpointSaver checkpointSaver)
            throws GraphStateException {
        CompiledGraph<SupportState> graph = builder.build().compile(
                CompileConfig.builder()
                        .checkpointSaver(checkpointSaver)
                        .build());

        if (log.isDebugEnabled()) {
            log.debug("Topología del agente (Mermaid):\n{}",
                    graph.getGraph(GraphRepresentation.Type.MERMAID, "L1 Support Copilot").content());
        }
        return graph;
    }
}
