package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EnumParsingTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    @DisplayName("Parsing ignores the default locale: lowercase i still maps to I under Turkish")
    void turkishLocaleDoesNotBreakParsing() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertThat(Intent.fromLlm("tickets")).isEqualTo(Intent.TICKETS);
        assertThat(Confirmation.fromState(" yes ")).isEqualTo(Confirmation.YES);
        assertThat(Treatment.fromState("usted")).isEqualTo(Treatment.USTED);
    }

    @Test
    @DisplayName("Blank and unknown values keep each enum's own fallback")
    void fallbacksPerEnum() {
        assertThat(Intent.fromLlm(null)).isEqualTo(Intent.NONE);
        assertThat(Intent.fromLlm("bogus")).isEqualTo(Intent.OTHER);
        assertThat(Verdict.fromLlm(" ")).isEqualTo(Verdict.DOUBT);
        assertThat(Awaiting.fromState("bogus")).isEqualTo(Awaiting.NOTHING);
        assertThat(Navigation.fromLlm("bogus")).isEqualTo(Navigation.NONE);
    }
}
