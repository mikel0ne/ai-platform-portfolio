package io.github.mikel0ne.l1copilot.dto.result;

import java.io.Serializable;

public record ReturnCheck(Long orderId, boolean returnable, String status, String description)
        implements Serializable {
}
