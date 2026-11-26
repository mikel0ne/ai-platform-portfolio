package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface TicketWriter {

    @SystemMessage(fromResource = "/prompts/ticket_writer.txt")
    @UserMessage("{{raw}}")
    TicketDraft draft(@V("raw") String raw);

    record TicketDraft(String subject, String description) {
    }
}
