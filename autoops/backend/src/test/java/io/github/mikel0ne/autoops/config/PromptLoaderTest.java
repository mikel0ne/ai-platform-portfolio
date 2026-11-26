package io.github.mikel0ne.autoops.config;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLoaderTest {

    @Test
    void a_second_load_returns_the_cached_instance() {
        var loader = new PromptLoader();

        String first = loader.load("crag_rewrite.txt");

        assertThat(loader.load("crag_rewrite.txt")).isSameAs(first);
    }

    @Test
    void a_missing_prompt_fails_every_time_and_is_not_cached() {
        var loader = new PromptLoader();

        assertThatThrownBy(() -> loader.load("missing.txt")).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> loader.load("missing.txt")).isInstanceOf(UncheckedIOException.class);
    }
}
