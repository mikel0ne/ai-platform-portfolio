package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.result.ReturnCheck;
import io.github.mikel0ne.l1copilot.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReturnCheckNodeTest {

    private final OrderService orderService = mock(OrderService.class);
    private final ReturnCheckNode node = new ReturnCheckNode(orderService);

    private Map<String, Object> run(ReturnCheck check, String confirmation) {
        when(orderService.checkReturnable(7L, "u1")).thenReturn(check);
        Map<String, Object> data = new HashMap<>();
        data.put(SupportState.USER_ID, "u1");
        data.put(SupportState.ORDER_ID, 7L);
        data.put(SupportState.CONFIRMATION, confirmation);
        return node.apply(new SupportState(data));
    }

    private void assertNotDeliveredBranch(Map<String, Object> updates, ReturnCheck check) {
        assertThat((String) updates.get(SupportState.TOOL_RESULT)).contains(check.description());
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");
        assertThat(updates).containsEntry(SupportState.RESOLVED, true);
    }

    @Test
    @DisplayName("A missing status falls in the generic not-delivered branch")
    void nullStatusIsNotDelivered() {
        ReturnCheck check = new ReturnCheck(7L, false, null, "Pedido #7 en camino");

        assertNotDeliveredBranch(run(check, "NONE"), check);
    }

    @Test
    @DisplayName("An unknown status falls in the generic not-delivered branch")
    void unknownStatusIsNotDelivered() {
        ReturnCheck check = new ReturnCheck(7L, false, "BOGUS", "Pedido #7 en camino");

        assertNotDeliveredBranch(run(check, "NONE"), check);
    }

    @Test
    @DisplayName("A return already requested shows the return card")
    void returnRequestedShowsCard() {
        ReturnCheck check = new ReturnCheck(7L, false, "RETURN_REQUESTED", "Pedido #7");

        Map<String, Object> updates = run(check, "NONE");

        assertThat(updates).containsKey(SupportState.RETURN_CARDS);
        assertThat((String) updates.get(SupportState.TOOL_RESULT)).contains("YA está iniciada");
    }

    @Test
    @DisplayName("A cancelled order cannot be returned")
    void cancelledOrderText() {
        ReturnCheck check = new ReturnCheck(7L, false, "CANCELLED", "Pedido #7");

        Map<String, Object> updates = run(check, "NONE");

        assertThat((String) updates.get(SupportState.TOOL_RESULT)).contains("está cancelado");
        assertThat(updates).doesNotContainKey(SupportState.RETURN_CARDS);
    }

    @Test
    @DisplayName("A returnable order with a YES confirmation only closes the question")
    void confirmedReturnOnlyClearsAwaiting() {
        ReturnCheck check = new ReturnCheck(7L, true, "DELIVERED", "Pedido #7");

        Map<String, Object> updates = run(check, "YES");

        assertThat(updates).containsOnlyKeys(SupportState.RETURNABLE, SupportState.AWAITING);
        assertThat(updates).containsEntry(SupportState.RETURNABLE, true);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");
    }
}
