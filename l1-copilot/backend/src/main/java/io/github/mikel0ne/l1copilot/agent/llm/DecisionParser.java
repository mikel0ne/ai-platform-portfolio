package io.github.mikel0ne.l1copilot.agent.llm;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.Set;

/**
 * Turns the raw verifier output into a {@link Verifier.Decision}, tolerating code fences and raw
 * control characters inside strings; anything unparseable degrades to DOUBT instead of failing the turn.
 */
@Slf4j
final class DecisionParser {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final Set<String> VERDICTS = Set.of("SURE", "DOUBT", "REJECT");

    private DecisionParser() {
    }

    static Verifier.Decision parse(String raw) {
        try {
            JsonNode node = MAPPER.readTree(sanitize(raw));
            String verdict = node.path("verdict").asString("").trim().toUpperCase(Locale.ROOT);
            if (!VERDICTS.contains(verdict)) {
                throw new IllegalArgumentException("missing or unknown verdict: '" + verdict + "'");
            }
            return new Verifier.Decision(verdict, node.path("reason").asString(""));
        } catch (Exception e) {
            log.warn("Verifier output not parseable, degrading to DOUBT ({}): {}",
                    e.getMessage(), abbreviate(raw));
            return new Verifier.Decision("DOUBT", "verifier output not parseable");
        }
    }

    static String sanitize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("no JSON object");
        }
        StringBuilder out = new StringBuilder(end - start + 1);
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i <= end; i++) {
            char c = raw.charAt(i);
            if (inString && !escaped && c < 0x20) {
                switch (c) {
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append(' ');
                }
                continue;
            }
            out.append(c);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            }
        }
        return out.toString();
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        String flat = raw.replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
