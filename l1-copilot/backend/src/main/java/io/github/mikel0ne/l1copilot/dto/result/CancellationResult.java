package io.github.mikel0ne.l1copilot.dto.result;

import java.io.Serializable;

public record CancellationResult(Long orderId, boolean cancelled, String reason)
        implements Serializable {
}
