package io.github.mikel0ne.l1copilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 2000) String message) {
}
