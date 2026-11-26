package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.E2eSeverity.Observation;
import io.github.mikel0ne.l1copilot.agent.E2eSeverity.Severity;
import io.github.mikel0ne.l1copilot.agent.RouterGoldenCases.GoldenCase;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import io.github.mikel0ne.l1copilot.agent.llm.Responder;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter.TicketDraft;
import io.github.mikel0ne.l1copilot.dto.result.CancellationCheck;
import io.github.mikel0ne.l1copilot.dto.result.CancellationResult;
import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;
import io.github.mikel0ne.l1copilot.dto.result.ReturnCheck;
import io.github.mikel0ne.l1copilot.dto.result.ReturnResult;
import io.github.mikel0ne.l1copilot.dto.result.ShipmentStatus;
import io.github.mikel0ne.l1copilot.dto.result.TicketCreated;
import io.github.mikel0ne.l1copilot.service.OrderService;
import io.github.mikel0ne.l1copilot.service.TicketService;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * Replays each golden case the router got wrong through the real graph, with the router forced to its observed
 * decision, the real verifier and permissive simulated tools (every order cancellable and returnable).
 */
@SpringBootTest(properties = {"copilot.turn-log.file.enabled=false", "copilot.turn-log.db.enabled=false"})
@EnabledIfEnvironmentVariable(named = "ROUTER_E2E_EVAL", matches = "true")
class RouterFailureE2eEvalTest {

    private static final String USER = "e2e-user";
    private static final long ONLY_ORDER = 501L;

    @MockitoBean
    private IntentClassifier classifier;

    @MockitoBean
    private Responder responder;

    @MockitoBean(name = "chattyResponder")
    private Responder chattyResponder;

    @MockitoBean
    private TicketWriter ticketWriter;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private TicketService ticketService;

    @Autowired
    private CompiledGraph<SupportState> supportAgent;

    record RouterFailure(int caseIndex, String message, String intent, Long orderId, String confirmation,
                         String navigation, boolean orderRelated) {

        RouterDecision decision() {
            return new RouterDecision(intent, orderId, confirmation, navigation, orderRelated);
        }
    }

    private record Result(GoldenCase golden, Intent routed, Severity severity, Observation seen) {
    }

    @Test
    @DisplayName("No router failure ends in an unrequested action")
    void routerFailuresCauseNoGraveOutcome() throws Exception {
        List<GoldenCase> cases = RouterGoldenCases.load();
        File source = new File(System.getenv().getOrDefault("ROUTER_E2E_FAILURES", RouterEvalTest.FAILURES_FILE));
        List<RouterFailure> failures = List.of(new JsonMapper().readValue(source, RouterFailure[].class));
        long pauseMs = Long.parseLong(System.getenv().getOrDefault("ROUTER_E2E_PAUSE_MS", "4000"));

        List<Result> results = new ArrayList<>();
        for (RouterFailure failure : failures) {
            GoldenCase golden = cases.get(failure.caseIndex());
            assertThat(golden.message()).as("stale failures file").isEqualTo(failure.message());
            pause(pauseMs);
            results.add(replay(failure, golden));
        }

        System.out.println(report(cases, results));
        int maxGrave = Integer.parseInt(System.getenv().getOrDefault("ROUTER_E2E_MAX_GRAVE", "0"));
        assertThat(results.stream().filter(r -> r.severity() == Severity.GRAVE).map(r -> r.golden().message()))
                .as("router failures that ended in an unrequested action")
                .hasSizeLessThanOrEqualTo(maxGrave);
    }

    private Result replay(RouterFailure failure, GoldenCase golden) {
        Mockito.reset(classifier, responder, chattyResponder, ticketWriter, orderService, ticketService);
        stubCollaborators(failure.decision());

        SupportState end = supportAgent.invoke(input(golden),
                        RunnableConfig.builder().threadId("e2e-" + failure.caseIndex()).build())
                .orElseThrow();

        Set<Intent> executed = EnumSet.noneOf(Intent.class);
        if (invoked(orderService, "cancelOrder")) {
            executed.add(Intent.CANCEL);
        }
        if (invoked(orderService, "requestReturn")) {
            executed.add(Intent.RETURN);
        }
        if (invoked(ticketService, "createTicket")) {
            executed.add(Intent.SUPPORT);
        }
        Set<Intent> offered = EnumSet.noneOf(Intent.class);
        end.pendingCancelId().ifPresent(id -> offered.add(Intent.CANCEL));
        end.pendingReturnId().ifPresent(id -> offered.add(Intent.RETURN));
        if (end.awaiting() == Awaiting.TICKET_INFO) {
            offered.add(Intent.SUPPORT);
        }
        boolean judged = end.toolResult().startsWith("CLARIFY:");

        Intent routed = Intent.fromLlm(failure.intent());
        Observation seen = new Observation(executed, offered, judged);
        return new Result(golden, routed, E2eSeverity.classify(
                Intent.fromLlm(golden.expectedIntent()), golden.expectedConfirmation(), routed, seen), seen);
    }

    private static boolean invoked(Object mock, String method) {
        return Mockito.mockingDetails(mock).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals(method));
    }

    private void stubCollaborators(RouterDecision forced) {
        when(classifier.classify(any(), any(), any(), any(), any(), any())).thenReturn(forced);
        when(responder.write(any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(), any()))
                .thenReturn("ECHO");
        when(chattyResponder.write(any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(), any()))
                .thenReturn("ECHO");
        when(ticketWriter.draft(any())).thenReturn(new TicketDraft("Incidencia", "Descripción"));

        when(orderService.getRecentOrders(any())).thenReturn(List.of(order(ONLY_ORDER)));
        when(orderService.findOrder(any(), any())).thenAnswer(inv -> Optional.of(order(inv.getArgument(0))));
        when(orderService.trackShipment(any(), any())).thenAnswer(inv -> new ShipmentStatus(
                inv.getArgument(0), "Producto", "SHIPPED", LocalDate.now().plusDays(2), "Transportista", null));
        when(orderService.checkCancellable(any(), any())).thenAnswer(inv -> new CancellationCheck(
                inv.getArgument(0), true, "SHIPPED", "El pedido se puede cancelar"));
        when(orderService.checkReturnable(any(), any())).thenAnswer(inv -> new ReturnCheck(
                inv.getArgument(0), true, "DELIVERED", "El pedido se puede devolver"));
        when(orderService.cancelOrder(any(), any())).thenAnswer(inv -> new CancellationResult(
                inv.getArgument(0), true, "Pedido cancelado"));
        when(orderService.requestReturn(any(), any())).thenAnswer(inv -> new ReturnResult(
                inv.getArgument(0), true, "RET-1", "Devolución iniciada"));
        when(ticketService.listTickets(any())).thenReturn(List.of());
        when(ticketService.createTicket(any(), any(), any(), any()))
                .thenReturn(new TicketCreated(1L, "Incidencia", "OPEN", Instant.now()));
    }

    private static OrderSummary order(long id) {
        return new OrderSummary(id, "Producto", "SHIPPED", LocalDate.now().minusDays(3), null);
    }

    private static Map<String, Object> input(GoldenCase golden) {
        List<Turn> history = new ArrayList<>(RouterGoldenCases.NO_HISTORY.equals(golden.history())
                ? List.of() : GoldenHistory.parse(golden.history()));
        history.add(Turn.user(golden.message()));

        Map<String, Object> input = new HashMap<>();
        input.put(SupportState.USER_ID, USER);
        input.put(SupportState.TREATMENT, Treatment.TU.name());
        input.put(SupportState.USER_MESSAGE, golden.message());
        input.put(SupportState.HISTORY, history);
        input.put(SupportState.AWAITING, golden.state().awaiting());
        input.put(SupportState.INTENT, golden.state().currentIntent());
        input.put(SupportState.CANDIDATES, golden.state().candidates());
        if (golden.state().focus() != null) {
            input.put(SupportState.ORDER_ID, golden.state().focus());
        }
        input.put(SupportState.CONFIRMATION, Confirmation.NONE.name());
        input.put(SupportState.TENTATIVE, false);
        input.put(SupportState.NAVIGATING, false);
        input.put(SupportState.PROPOSED_ORDER_ID, 0L);
        input.put(SupportState.TOOL_RESULT, "");
        input.put(SupportState.REPLY, "");
        input.put(SupportState.RESOLVED, false);
        input.put(SupportState.ORDER_CARDS, List.of());
        input.put(SupportState.TICKET_CARDS, List.of());
        input.put(SupportState.RETURN_CARDS, List.of());
        input.put(SupportState.PENDING_CANCEL_ID, 0L);
        input.put(SupportState.PENDING_RETURN_ID, 0L);
        return input;
    }

    private static String report(List<GoldenCase> cases, List<Result> results) {
        Map<String, int[]> rows = new TreeMap<>();
        cases.forEach(c -> rows.computeIfAbsent(c.category(), k -> new int[5])[0]++);
        results.forEach(r -> {
            int[] row = rows.get(r.golden().category());
            row[1]++;
            row[2 + r.severity().ordinal()]++;
        });
        StringBuilder out = new StringBuilder("\n=== Router failures end to end ===\n");
        out.append(String.format("  %-22s %-5s %5s %12s %6s %9s %5s%n",
                "category", "", "cases", "router fails", "GRAVE", "MITIGADO", "LEVE"));
        rows.forEach((category, row) -> out.append(String.format("  %-22s %-5s %5d %12d %6d %9d %5d%n",
                category, RouterGoldenCases.SECURITY_CATEGORIES.contains(category) ? "(sec)" : "",
                row[0], row[1], row[2], row[3], row[4])));
        long grave = results.stream().filter(r -> r.severity() == Severity.GRAVE).count();
        out.append(String.format("  raw router: %d/%d correct | after chain (not GRAVE): %d/%d%n",
                cases.size() - results.size(), cases.size(), cases.size() - grave, cases.size()));
        results.forEach(r -> out.append(String.format(
                "  %-8s [%s] expected %s, routed %s | executed=%s offered=%s judged=%s | %s%n",
                r.severity(), r.golden().category(), r.golden().expectedIntent(), r.routed(),
                r.seen().executed(), r.seen().offered(), r.seen().judged(), r.golden().message().replace('\n', ' '))));
        return out.toString();
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
