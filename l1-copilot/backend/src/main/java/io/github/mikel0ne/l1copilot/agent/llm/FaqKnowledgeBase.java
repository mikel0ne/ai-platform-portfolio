package io.github.mikel0ne.l1copilot.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class FaqKnowledgeBase {

    private final String content;

    public FaqKnowledgeBase(@Value("classpath:faq.md") Resource resource) {
        try (var inputStream = resource.getInputStream()) {
            this.content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("FAQ loaded: {} characters", content.length());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load faq.md", e);
        }
    }

    public String content() {
        return content;
    }
}
