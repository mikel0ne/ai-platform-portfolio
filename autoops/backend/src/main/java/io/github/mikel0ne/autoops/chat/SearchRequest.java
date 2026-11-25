package io.github.mikel0ne.autoops.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchRequest(
        @NotBlank @Size(max = SearchRequest.MAX_QUERY_LENGTH, message = "must be at most 2000 characters") String query) {

    public static final int MAX_QUERY_LENGTH = 2000;
}
