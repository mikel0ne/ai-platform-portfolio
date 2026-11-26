package io.github.mikel0ne.l1copilot.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CharsetGuardTest {

    @Test
    void utf8_is_accepted() {
        assertThatCode(() -> CharsetGuard.requireUtf8(StandardCharsets.UTF_8)).doesNotThrowAnyException();
    }

    @Test
    void any_other_charset_fails_the_start_with_the_fix_in_the_message() {
        assertThatThrownBy(() -> CharsetGuard.requireUtf8(java.nio.charset.Charset.forName("windows-1252")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("windows-1252")
                .hasMessageContaining("-Dfile.encoding=UTF-8");
    }

    @Test
    void the_test_jvm_runs_with_utf8_as_default() {
        assertThatCode(new CharsetGuard()::check).doesNotThrowAnyException();
    }
}
