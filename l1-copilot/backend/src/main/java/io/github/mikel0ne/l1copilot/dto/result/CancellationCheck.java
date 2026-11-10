package io.github.mikel0ne.l1copilot.dto.result;

import java.io.Serializable;

public record CancellationCheck(Long orderId, boolean cancellable, String status, String description)
        implements Serializable {
}
