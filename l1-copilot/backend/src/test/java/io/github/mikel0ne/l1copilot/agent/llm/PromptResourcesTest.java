package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class PromptResourcesTest {

    private static final Map<Class<?>, List<String>> VARIABLES = Map.of(
            IntentClassifier.class, List.of("{{currentIntent}}", "{{awaiting}}", "{{candidates}}", "{{focusOrder}}"),
            Responder.class, List.of(),
            RawVerifier.class, List.of(),
            TicketWriter.class, List.of());

    @Test
    void every_system_prompt_resource_is_valid_utf8_with_its_variables() throws IOException {
        for (var entry : VARIABLES.entrySet()) {
            String path = fromResource(entry.getKey());
            String text;
            try (InputStream in = entry.getKey().getResourceAsStream(path)) {
                assertThat(in).as(path).isNotNull();
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            assertThat(text).as(path).isNotBlank().doesNotContain("\uFFFD", "\u00C3", "\u00C2");
            entry.getValue().forEach(variable -> assertThat(text).as(path).contains(variable));
        }
    }

    private static String fromResource(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(m -> m.getAnnotation(SystemMessage.class))
                .filter(Objects::nonNull)
                .findFirst().orElseThrow().fromResource();
    }
}
