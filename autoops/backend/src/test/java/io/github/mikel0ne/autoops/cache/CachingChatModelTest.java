package io.github.mikel0ne.autoops.cache;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachingChatModelTest {

    private static ChatRequest request(double temperature) {
        return ChatRequest.builder().messages(UserMessage.from("same prompt")).temperature(temperature).build();
    }

    @Test
    void the_same_prompt_with_other_parameters_is_another_cache_entry() {
        assertThat(CachingChatModel.keyMaterial(request(0.0)))
                .isNotEqualTo(CachingChatModel.keyMaterial(request(0.7)))
                .isEqualTo(CachingChatModel.keyMaterial(request(0.0)));
    }
}
