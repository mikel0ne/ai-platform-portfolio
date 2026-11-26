package io.github.mikel0ne.autoops.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    private String read(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load prompt prompts/" + name, e);
        }
    }
}
