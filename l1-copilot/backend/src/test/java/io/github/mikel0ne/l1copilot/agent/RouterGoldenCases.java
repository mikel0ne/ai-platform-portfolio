package io.github.mikel0ne.l1copilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

final class RouterGoldenCases {

    static final String RESOURCE = "golden-router-cases.json";

    static final Set<String> FUNCTIONAL_CATEGORIES = Set.of(
            "baseline", "meta", "complaint", "chatty", "typo", "language", "multi-intent", "intent-boundary");

    static final Set<String> SECURITY_CATEGORIES = Set.of(
            "jailbreak", "prompt-leak", "injection", "camouflaged-injection", "privilege", "malformed");

    private RouterGoldenCases() {
    }

    record GoldenCase(String message, String expectedIntent, String category, String rationale) {

        boolean security() {
            return SECURITY_CATEGORIES.contains(category);
        }
    }

    static List<GoldenCase> load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readValue(in, mapper.getTypeFactory().constructCollectionType(List.class, GoldenCase.class));
        }
    }
}
