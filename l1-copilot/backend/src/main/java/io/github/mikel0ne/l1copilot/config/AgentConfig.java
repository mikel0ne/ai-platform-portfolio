package io.github.mikel0ne.l1copilot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.github.mikel0ne.l1copilot.agent.SupportAgentGraph;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.Responder;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter;
import io.github.mikel0ne.l1copilot.agent.llm.Verifier;
import io.github.mikel0ne.l1copilot.config.qualifier.ChattyModel;
import io.github.mikel0ne.l1copilot.config.qualifier.ChattyResponder;
import io.github.mikel0ne.l1copilot.config.qualifier.Deterministic;
import io.github.mikel0ne.l1copilot.config.qualifier.ResponseModel;
import io.github.mikel0ne.l1copilot.config.qualifier.TicketModel;
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
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public IntentClassifier intentClassifier(@Deterministic ChatModel chatModel) {
        return AiServices.create(IntentClassifier.class, chatModel);
    }

    @Bean
    @Primary
    public Responder responder(@ResponseModel ChatModel responseChatModel) {
        return AiServices.create(Responder.class, responseChatModel);
    }

    @Bean
    @ChattyResponder
    public Responder chattyResponder(@ChattyModel ChatModel chatChatModel) {
        return AiServices.create(Responder.class, chatChatModel);
    }

    @Bean
    public TicketWriter ticketWriter(@TicketModel ChatModel ticketChatModel) {
        return AiServices.create(TicketWriter.class, ticketChatModel);
    }

    @Bean
    public Verifier verifier(@Deterministic ChatModel chatModel) {
        return AiServices.create(Verifier.class, chatModel);
    }

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
            log.debug("Agent topology (Mermaid):\n{}",
                    graph.getGraph(GraphRepresentation.Type.MERMAID, "L1 Support Copilot").content());
        }
        return graph;
    }
}
