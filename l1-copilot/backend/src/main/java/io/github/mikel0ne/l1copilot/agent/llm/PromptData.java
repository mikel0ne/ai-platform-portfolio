package io.github.mikel0ne.l1copilot.agent.llm;

import io.github.mikel0ne.l1copilot.agent.Turn;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders untrusted text (user turns, agent replies quoting third parties, catalog descriptions) as JSON
 * values, so the content can neither break a line nor close its own quotes to imitate another turn.
 */
public final class PromptData {

    private static final JsonMapper MAPPER = new JsonMapper();
    // Line breaks JSON would leave raw (U+0085, U+2028, U+2029) and invisible format characters such as bidi overrides or tag characters.
    private static final Pattern UNESCAPED_BREAKS = Pattern.compile("[\\u0085\\u2028\\u2029]");
    private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");

    private PromptData() {
    }

    public static String turns(List<Turn> turns) {
        return turns.stream().map(PromptData::turn).collect(Collectors.joining("\n"));
    }

    public static String strings(List<String> values) {
        return json(values.stream().map(PromptData::clean).toList());
    }

    static String turn(Turn turn) {
        Map<String, String> line = new LinkedHashMap<>();
        line.put("role", turn.role().name());
        line.put("text", clean(turn.text()));
        return json(line);
    }

    static String clean(String text) {
        String breaks = UNESCAPED_BREAKS.matcher(text == null ? "" : text).replaceAll("\n");
        return INVISIBLE.matcher(breaks).replaceAll("");
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot render prompt data", e);
        }
    }
}
