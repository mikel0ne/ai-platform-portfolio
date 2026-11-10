package io.github.mikel0ne.l1copilot.dto;

import java.io.Serializable;

public record TicketCardDto(Long id, String code, String title, String status, String createdAt)
        implements Serializable {
    public static String code(long id) {
        return String.format("SUP-%04d", id);
    }
}
