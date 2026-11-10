package io.github.mikel0ne.l1copilot.dto.result;

import java.io.Serializable;
import java.time.LocalDate;

public record OrderSummary(Long id, String description, String status, LocalDate orderDate, String imageUrl)
        implements Serializable {
}
