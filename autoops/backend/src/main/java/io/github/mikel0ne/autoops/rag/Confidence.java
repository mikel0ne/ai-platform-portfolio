package io.github.mikel0ne.autoops.rag;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum Confidence {

    HIGH, MEDIUM, LOW, NONE;

    @JsonValue
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
