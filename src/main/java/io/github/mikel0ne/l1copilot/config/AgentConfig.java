package io.github.mikel0ne.l1copilot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.github.mikel0ne.l1copilot.agent.SupportAgentGraph;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.Responder;
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

    /**
     * Memoria conversacional en memoria de proceso (ADR-6): suficiente para el
     * prototipo single-replica. Punto de extensión: implementar
     * {@link BaseCheckpointSaver} sobre Postgres para escalar horizontalmente.
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
