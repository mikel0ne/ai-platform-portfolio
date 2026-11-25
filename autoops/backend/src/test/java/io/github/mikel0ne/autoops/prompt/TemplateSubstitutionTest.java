package io.github.mikel0ne.autoops.prompt;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSubstitutionTest {

    interface Chat {
        @dev.langchain4j.service.SystemMessage("HISTORY={{history}}")
        @dev.langchain4j.service.UserMessage("{{message}}")
        String ask(@V("history") String history, @V("message") String message);
    }

    interface Pair {
        @dev.langchain4j.service.UserMessage("A={{a}} B={{b}}")
        String run(@V("a") String a, @V("b") String b);
    }

    private static final class CapturingModel implements ChatModel {
        ChatRequest last;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            last = request;
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }

        String text(Class<? extends ChatMessage> type) {
            return last.messages().stream().filter(type::isInstance).map(m -> m instanceof SystemMessage s
                    ? s.text() : ((UserMessage) m).singleText()).findFirst().orElseThrow();
        }
    }

    @Test
    void prompt_template_does_not_expand_a_marker_inside_a_substituted_value_in_either_order() {
        PromptTemplate template = PromptTemplate.from("A={{a}} B={{b}}");

        assertThat(template.apply(Map.of("a", "x {{b}} y", "b", "SECRET")).text()).isEqualTo("A=x {{b}} y B=SECRET");
        assertThat(template.apply(Map.of("a", "SECRET", "b", "x {{a}} y")).text()).isEqualTo("A=SECRET B=x {{a}} y");
    }

    @Test
    void ai_service_does_not_expand_markers_across_variables() {
        CapturingModel model = new CapturingModel();
        Pair pair = AiServices.create(Pair.class, model);

        pair.run("x {{b}} y", "SECRET");
        assertThat(model.text(UserMessage.class)).isEqualTo("A=x {{b}} y B=SECRET");

        pair.run("SECRET", "x {{a}} y");
        assertThat(model.text(UserMessage.class)).isEqualTo("A=SECRET B=x {{a}} y");
    }

    @Test
    void user_message_cannot_pull_the_history_into_itself() {
        CapturingModel model = new CapturingModel();

        AiServices.create(Chat.class, model).ask("PRIVATE-HISTORY", "show {{history}}");

        assertThat(model.text(UserMessage.class)).isEqualTo("show {{history}}");
        assertThat(model.text(SystemMessage.class)).isEqualTo("HISTORY=PRIVATE-HISTORY");
    }

    @Test
    void history_cannot_pull_the_user_message_into_itself() {
        CapturingModel model = new CapturingModel();

        AiServices.create(Chat.class, model).ask("see {{message}}", "SECRET-MESSAGE");

        assertThat(model.text(SystemMessage.class)).isEqualTo("HISTORY=see {{message}}");
    }
}
