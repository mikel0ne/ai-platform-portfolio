package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;

public record ReturnResult(Long orderId, boolean returned, String reference, String reason)
        implements Serializable {
}
