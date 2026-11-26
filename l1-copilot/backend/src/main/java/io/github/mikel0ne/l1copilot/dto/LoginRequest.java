package io.github.mikel0ne.l1copilot.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String userId) {
}
