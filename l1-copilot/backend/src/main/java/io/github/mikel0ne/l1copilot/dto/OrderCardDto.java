package io.github.mikel0ne.l1copilot.dto;

import java.io.Serializable;

public record OrderCardDto(Long id, String description, String status, String imageUrl) implements Serializable {
}
