package io.github.mikel0ne.l1copilot.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Base de conocimiento estática de preguntas frecuentes (envíos, devoluciones,
 * pagos, garantía...). Se carga una sola vez desde {@code faq.md} y se entrega
 * tal cual a {@code Responder} como {@code TOOL_RESULT} para la skill FAQ.
 */
@Component
@Slf4j
public class FaqKnowledgeBase {

    private final String content;

    public FaqKnowledgeBase(@Value("classpath:faq.md") Resource resource) {
        try (var inputStream = resource.getInputStream()) {
            this.content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("FAQ cargada: {} caracteres", content.length());
        } catch (IOException e) {
            throw new UncheckedIOException("No se ha podido cargar faq.md", e);
        }
    }

    public String content() {
        return content;
    }
}
