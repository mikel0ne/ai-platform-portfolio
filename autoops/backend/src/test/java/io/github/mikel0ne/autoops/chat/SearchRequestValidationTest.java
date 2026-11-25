package io.github.mikel0ne.autoops.chat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void accepts_a_query_at_the_limit() {
        assertThat(validator.validate(new SearchRequest("a".repeat(SearchRequest.MAX_QUERY_LENGTH)))).isEmpty();
    }

    @Test
    void rejects_a_query_over_the_limit() {
        assertThat(validator.validate(new SearchRequest("a".repeat(SearchRequest.MAX_QUERY_LENGTH + 1))))
                .singleElement()
                .satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("query"));
    }

    @Test
    void rejects_a_blank_query() {
        assertThat(validator.validate(new SearchRequest("  "))).hasSize(1);
    }
}
