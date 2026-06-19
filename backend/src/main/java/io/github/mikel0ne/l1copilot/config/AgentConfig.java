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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wiring del agente: AiServices (router y redactor), checkpointer y grafo compilado.
 * <p>
 * Cada AiService se conecta al modelo con la temperatura adecuada a su tarea
 * (ver {@link Config}): router y verificador al determinista (0.0), el redactor de
 * tickets al de tickets (0.2), el redactor de negocio al de respuesta (0.35) y el
 * redactor de charla informal ({@code chattyResponder}) al de chat (0.7).
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public IntentClassifier intentClassifier(ChatModel chatModel) {
        return AiServices.create(IntentClassifier.class, chatModel);
    }

    /** Redactor de las gestiones de negocio: temperatura media para un tono natural. */
    @Bean
    @Primary
    public Responder responder(@Qualifier("responseChatModel") ChatModel responseChatModel) {
        return AiServices.create(Responder.class, responseChatModel);
    }

    /**
     * Redactor de la charla informal (modo chatty): misma interfaz que {@link #responder},
     * pero sobre el modelo de mayor temperatura para respuestas más cálidas y variadas.
     */
    @Bean
    public Responder chattyResponder(@Qualifier("chatChatModel") ChatModel chatChatModel) {
        return AiServices.create(Responder.class, chatChatModel);
    }

    @Bean
    public TicketWriter ticketWriter(@Qualifier("ticketChatModel") ChatModel ticketChatModel) {
        return AiServices.create(TicketWriter.class, ticketChatModel);
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
