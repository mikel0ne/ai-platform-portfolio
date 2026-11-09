package io.github.mikel0ne.l1copilot.dto;

import java.util.List;

public record ChatResponse(String conversationId, String reply, String intent, boolean resolved,
                           List<OrderCardDto> orders, boolean ordersSelectable,
                           List<TicketCardDto> tickets,
                           List<ReturnCardDto> returns, ConfirmActionDto confirm) {
}
