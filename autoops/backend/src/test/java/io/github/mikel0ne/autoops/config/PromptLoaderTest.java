package io.github.mikel0ne.autoops.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"crag_grade.txt", "crag_multiquery.txt"})
    void prompts_loaded_through_langchain4j_resources_decode_as_utf8(String name) {
        String text = new PromptLoader().load(name);

        assertThat(text).isNotBlank().doesNotContain("\uFFFD", "\u00C3", "\u00C2");
    }
}
