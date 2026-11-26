package io.github.mikel0ne.l1copilot.agent;

import java.util.Locale;

final class EnumParsing {

    private EnumParsing() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String raw, E whenBlank, E whenUnknown) {
        if (raw == null || raw.isBlank()) {
            return whenBlank;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return whenUnknown;
        }
    }
}
