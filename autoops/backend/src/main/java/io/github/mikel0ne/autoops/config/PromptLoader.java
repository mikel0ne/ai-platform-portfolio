package io.github.mikel0ne.autoops.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Carga plantillas de prompt desde {@code resources/prompts/} (convención del doc 04 §4: un prompt por
 * fichero, versionado con el código; nunca hardcodeado).
 */
@Component
public class PromptLoader {

    /** Lee {@code prompts/<nombre>} del classpath. Falla rápido si no existe (error de empaquetado). */
    public String load(String nombre) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + nombre);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar el prompt prompts/" + nombre, e);
        }
    }
}
