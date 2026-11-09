package io.github.mikel0ne.autoops.chat;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(@NotBlank String query) {}
