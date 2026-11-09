package io.github.mikel0ne.autoops.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Component
public class PromptLoader {

    public String load(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load prompt prompts/" + name, e);
        }
    }
}
