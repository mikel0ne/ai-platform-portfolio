package io.github.mikel0ne.autoops.config;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private PromptTemplates() {
    }

    // Single pass: inserted values are never rescanned, so a value containing {{x}} stays literal.
    public static String render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
