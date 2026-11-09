package io.github.mikel0ne.l1copilot.agent;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import io.github.mikel0ne.l1copilot.agent.llm.Responder;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter.TicketDraft;
import io.github.mikel0ne.l1copilot.agent.llm.Verifier;
import org.mockito.ArgumentCaptor;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.domain.repository.TicketRepository;
import io.github.mikel0ne.l1copilot.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "langchain4j.gemini.api-key=test-key",

        "copilot.turn-log.file.enabled=false",
        "copilot.turn-log.db.enabled=false"})
@Transactional
class SupportAgentFlowTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private IntentClassifier classifier;

    @MockitoBean
    private Responder responder;

    @MockitoBean(name = "chattyResponder")
    private Responder chattyResponder;

    @MockitoBean
    private TicketWriter ticketWriter;

    @MockitoBean
    private Verifier verifier;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @BeforeEach
    void echoResponder() {
        when(responder.write(any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> "ECHO[" + inv.getArgument(2) + "]");

        when(chattyResponder.write(any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> "ECHO[" + inv.getArgument(2) + "]");

        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("SURE", "gestión clara"));
    }

    @Test
    @DisplayName("Reference scenario: ambiguity → proactive disambiguation → loop → tracking")
    void referenceScenario_trackingWithDisambiguation() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))

                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"))

                .thenReturn(new RouterDecision("TRACKING", 123L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-ref", "¿Dónde está mi pedido?");

        assertThat(turn1.intent()).isEqualTo("TRACKING");
        assertThat(turn1.resolved()).isFalse();

        assertThat(turn1.reply()).contains("#123").contains("#456").contains("Portátil");

        var turn2 = conversationService.chat("u-001", "conv-ref", "El portátil");

        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("IN_DELIVERY").contains("SEUR");
    }

    @Test
    @DisplayName("Cancellation with human-in-the-loop: asks for confirmation and only runs after the yes")
    void cancellationRequiresConfirmation() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 200L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", null, "YES", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-cancel", "Cancela el pedido 200");

        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("puede cancelarse");

        assertThat(orderRepository.findById(200L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CREATED);

        var turn2 = conversationService.chat("u-002", "conv-cancel", "Sí, cancélalo");

        assertThat(turn2.resolved()).isTrue();
        assertThat(orderRepository.findById(200L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancellation by button: the button command runs the cancellation without going through the LLM")
    void cancellationViaButtonIsDeterministic() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 202L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-cancel-btn", "quiero cancelar el 202");
        assertThat(turn1.resolved()).isFalse();

        assertThat(turn1.confirm()).isNotNull();
        assertThat(turn1.confirm().orderId()).isEqualTo(202L);
        assertThat(turn1.confirm().confirmPayload()).isEqualTo(UiAction.confirmCancel(202L));
        assertThat(turn1.confirm().warning()).contains("no se puede deshacer");

        assertThat(turn1.confirm().title()).contains("¿Confirma la cancelación");
        assertThat(turn1.confirm().title()).doesNotContain("Confirmas");

        assertThat(orderRepository.findById(202L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        var turn2 = conversationService.chat("u-002", "conv-cancel-btn", UiAction.confirmCancel(202L));
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.confirm()).isNull();
        assertThat(orderRepository.findById(202L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("After cancelling an order, the next turn offers the remaining orders again")
    void cancellationClearsOrderContextForNextTurn() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 456L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", null, "YES", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-cancel-reset", "Cancela el pedido 456");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("puede cancelarse");

        var turn2 = conversationService.chat("u-001", "conv-cancel-reset", "Sí, cancélalo");
        assertThat(turn2.resolved()).isTrue();
        assertThat(orderRepository.findById(456L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        var turn3 = conversationService.chat("u-001", "conv-cancel-reset", "Quiero cancelar otro");
        assertThat(turn3.resolved()).isFalse();
        assertThat(turn3.reply()).contains("#123").contains("#789").contains("CANCELLED");
    }

    @Test
    @DisplayName("Aborting the cancellation does NOT release the focus: the order stays active for the next turn")
    void abortingCancellationKeepsFocus() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 456L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", null, "NO", "NONE"))
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-cancel-abort", "Cancela el pedido 456");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("puede cancelarse");

        var turn2 = conversationService.chat("u-001", "conv-cancel-abort", "No, consérvalo");
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("descartada");
        assertThat(orderRepository.findById(456L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        var turn3 = conversationService.chat("u-001", "conv-cancel-abort", "¿y dónde está?");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("#456").contains("Ratón");
        assertThat(turn3.reply()).doesNotContain("#123").doesNotContain("#789");
    }

    @Test
    @DisplayName("Cancelling a shipped order is rejected by business code")
    void cancellationOfShippedOrderIsRejected() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 201L, "NONE", "NONE"));

        var turn = conversationService.chat("u-002", "conv-cancel-ko", "Cancela el pedido 201");

        assertThat(turn.resolved()).isTrue();
        assertThat(turn.reply()).contains("ya no puede cancelarse");
        assertThat(orderRepository.findById(201L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("Support: proposes the ticket (does not create it), asks for detail and creates it on confirmation")
    void supportProposesThenCreatesTicket() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        when(ticketWriter.draft(any())).thenReturn(new TicketDraft(
                "Monitor recibido con la pantalla agrietada",
                "El monitor llegó con la pantalla agrietada en la esquina superior y no enciende."));

        var turn1 = conversationService.chat("u-001", "conv-support", "No funciona");

        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("CONFIRMAR").contains("detalle");
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-001", "conv-support",
                "El monitor que compré llegó con la pantalla agrietada por la esquina superior y no enciende");

        assertThat(turn2.resolved()).isTrue();

        assertThat(turn2.reply()).contains("Ticket de soporte").contains("SUP-");

        assertThat(turn2.tickets()).hasSize(1);
        assertThat(turn2.tickets().get(0).code()).startsWith("SUP-");
        assertThat(turn2.tickets().get(0).id()).isNotNull();

        assertThat(turn2.tickets().get(0).title()).isEqualTo("Monitor recibido con la pantalla agrietada");

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketWriter).draft(rawCaptor.capture());
        assertThat(rawCaptor.getValue()).contains("No funciona").contains("agrietada");

        var tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc("u-001");
        assertThat(tickets).isNotEmpty();

        assertThat(tickets.get(0).getSubject()).isEqualTo("Monitor recibido con la pantalla agrietada");
        assertThat(tickets.get(0).getDescription())
                .doesNotContain("No funciona")
                .contains("agrietada");
    }

    @Test
    @DisplayName("Support on an order: first identifies the order (menu), then proposes the ticket")
    void supportOnOrderIdentifiesOrderFirst() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))

                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE", true))

                .thenReturn(new RouterDecision("SUPPORT", 123L, "NONE", "NONE", true));

        var turn1 = conversationService.chat("u-001", "conv-support-order",
                "tengo un problema con un pedido, me lo perdieron hace meses");

        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.tickets()).isEmpty();
        assertThat(turn1.reply()).contains("#123").contains("#456");

        var turn2 = conversationService.chat("u-001", "conv-support-order", "el portátil");

        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.tickets()).isEmpty();
        assertThat(turn2.reply()).contains("CONFIRMAR");
    }

    @Test
    @DisplayName("Support: not even a long, angry first message creates the ticket at once; it proposes it first")
    void supportDoesNotAutoCreateOnFirstLongMessage() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-support-long",
                "joven, yo hice la compra así que ya me dirán qué hacemos si ustedes no se acuerdan");

        assertThat(turn.resolved()).isFalse();
        assertThat(turn.tickets()).isEmpty();
        assertThat(turn.reply()).contains("CONFIRMAR");
    }

    @Test
    @DisplayName("Support: if the user declines the ticket at the gate, nothing is created and it reroutes")
    void supportDeclineAtGateCreatesNothingAndReroutes() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))

                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))

                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-support-no", "tengo un problema");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-001", "conv-support-no", "no, déjalo, mejor otra cosa");

        assertThat(turn2.tickets()).isEmpty();
        assertThat(turn2.reply()).contains("capacidades");

        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Support: if the user asks to cancel at the gate, the ticket is dropped and it goes to cancellation")
    void supportPivotToCancelAtGateDropsTicket() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))

                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))

                .thenReturn(new RouterDecision("CANCEL", 200L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-support-pivot", "esto va fatal");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-002", "conv-support-pivot", "no, mejor cancela el pedido 200");

        assertThat(turn2.tickets()).isEmpty();
        assertThat(turn2.reply()).contains("puede cancelarse");
        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Verify with DOUBT on an action: asks (clarify) instead of acting and creates nothing")
    void verifyDoubtAsksInsteadOfActing() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("DOUBT", "queja vaga, sin problema concreto"));

        var turn = conversationService.chat("u-001", "conv-verify", "menudo desastre de tienda, de verdad");

        assertThat(turn.resolved()).isFalse();
        assertThat(turn.tickets()).isEmpty();

        assertThat(turn.reply()).contains("CLARIFY");
        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Verify with REJECT on an action: asks, leading with alternatives, without seeding the ticket")
    void verifyRejectLeadsWithAlternatives() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("REJECT", "es una pregunta general, no una incidencia"));

        var turn = conversationService.chat("u-001", "conv-reject", "¿y cómo van las devoluciones?");

        assertThat(turn.tickets()).isEmpty();

        assertThat(turn.reply()).contains("CLARIFY").contains("alternativas");
        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Verify with DOUBT on a READ: carries on with a tentative tone (does not stop)")
    void verifyDoubtOnReadProceedsTentatively() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("DOUBT", "no está claro si quiere el envío"));

        var turn = conversationService.chat("u-001", "conv-tentative", "a ver mis cosas");

        assertThat(turn.reply()).contains("#123").contains("#456");
        verify(responder).write(any(), any(), any(), any(), anyBoolean(), any(), any(), eq(true), any());
    }

    @Test
    @DisplayName("Verify DOUBT → the user confirms the ticket: it is created WITHOUT asking again")
    void clarifyThenConfirmCreatesTicketWithoutReasking() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))

                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))

                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("DOUBT", "no está claro"));
        when(ticketWriter.draft(any())).thenReturn(new TicketDraft(
                "Monitor que no enciende", "El monitor recibido no enciende y llegó dañado."));

        var turn1 = conversationService.chat("u-001", "conv-clar", "tengo un lío con el monitor");
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-001", "conv-clar",
                "sí, ábrela: el monitor llegó dañado y no enciende, quiero que lo revise alguien");

        assertThat(turn2.tickets()).hasSize(1);
        assertThat(turn2.reply()).contains("Ticket de soporte");

        verify(verifier, times(1)).verify(any(), any(), any());

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(ticketWriter).draft(raw.capture());
        assertThat(raw.getValue()).contains("lío con el monitor").contains("no enciende");
    }

    @Test
    @DisplayName("Sticky focus: after checking an order, a message without an id still refers to THAT order")
    void focusStaysOnOrderAcrossTurns() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));
        when(ticketWriter.draft(any())).thenReturn(new TicketDraft(
                "Pedido entregado pero no recibido",
                "El cliente indica que el pedido figura como entregado pero no lo ha recibido."));

        var turn1 = conversationService.chat("u-001", "conv-focus", "¿Dónde está mi pedido 789?");
        assertThat(turn1.resolved()).isTrue();
        assertThat(turn1.reply()).contains("DELIVERED").contains("MRW");

        var turn2 = conversationService.chat("u-001", "conv-focus", "no lo he recibido");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("detalle");
        assertThat(turn2.tickets()).isEmpty();

        var turn3 = conversationService.chat("u-001", "conv-focus",
                "El monitor Dell figura como entregado pero no lo he recibido en casa, llevo días esperándolo");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.tickets()).hasSize(1);

        Long newTicketId = turn3.tickets().get(0).id();
        assertThat(ticketRepository.findById(newTicketId).orElseThrow().getOrderId())
                .isEqualTo(789L);
    }

    @Test
    @DisplayName("Back to list: with an order in focus, 'show my orders' releases the focus and offers them all again")
    void backToListReleasesFocusAndReListsOrders() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))

                .thenReturn(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        var turn1 = conversationService.chat("u-001", "conv-back", "Estado del pedido 789");
        assertThat(turn1.resolved()).isTrue();

        var turn2 = conversationService.chat("u-001", "conv-back", "enséñame todos mis pedidos");

        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("#123").contains("#456").contains("#789");
    }

    @Test
    @DisplayName("Back to main menu: with an order in focus, goes back to fallback without re-listing orders")
    void backToMenuReleasesFocusAndShowsCapabilities() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))

                .thenReturn(new RouterDecision("OTHER", null, "NONE", "BACK_TO_MENU"));

        var turn1 = conversationService.chat("u-001", "conv-menu", "Estado del pedido 789");
        assertThat(turn1.resolved()).isTrue();

        var turn2 = conversationService.chat("u-001", "conv-menu", "volver al menú principal");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("dudas generales");
        assertThat(turn2.reply()).doesNotContain("#123").doesNotContain("#456").doesNotContain("#789");
    }

    @Test
    @DisplayName("No action before validation: a CANCEL rejected by verify does NOT move the focus")
    void rejectedActionNeverCommitsFocus() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", 123L, "NONE", "NONE"))

                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("SURE", "consulta clara"))
                .thenReturn(new Verifier.Decision("REJECT", "no pide cancelar nada"))
                .thenReturn(new Verifier.Decision("SURE", "consulta clara"));

        var turn1 = conversationService.chat("u-001", "conv-defer", "Estado del pedido 789");
        assertThat(turn1.reply()).contains("DELIVERED").contains("MRW");

        var turn2 = conversationService.chat("u-001", "conv-defer", "cancela el 123 digo no sé");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("CLARIFY");

        var turn3 = conversationService.chat("u-001", "conv-defer", "¿y por dónde va?");
        assertThat(turn3.reply()).contains("DELIVERED").contains("MRW");
        assertThat(turn3.reply()).doesNotContain("IN_DELIVERY").doesNotContain("SEUR");
    }

    @Test
    @DisplayName("FAQ: a general question is answered from the store knowledge base")
    void faqAnswersFromKnowledgeBase() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("FAQ", null, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-faq", "¿Cuál es la política de devoluciones?");

        assertThat(turn.resolved()).isTrue();
        assertThat(turn.reply()).contains("devoluci");
    }

    @Test
    @DisplayName("Digression: an FAQ while choosing an order is answered and then the choice resumes")
    void faqDigressionDuringOrderChoiceResumes() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))

                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"))

                .thenReturn(new RouterDecision("FAQ", null, "NONE", "NONE"))

                .thenReturn(new RouterDecision("TRACKING", 123L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-digress", "¿Dónde está mi pedido?");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("#123").contains("#456");

        var turn2 = conversationService.chat("u-001", "conv-digress", "oye, ¿y las devoluciones cómo van?");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("devoluci");

        var turn3 = conversationService.chat("u-001", "conv-digress", "el portátil");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("IN_DELIVERY").contains("SEUR");
    }

    @Test
    @DisplayName("Sticky focus: an FAQ does not steal the focus of the current order")
    void faqKeepsOrderFocus() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))

                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))

                .thenReturn(new RouterDecision("FAQ", null, "NONE", "NONE"))

                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-faq-focus", "Estado del pedido 789");
        assertThat(turn1.resolved()).isTrue();
        assertThat(turn1.reply()).contains("DELIVERED").contains("MRW");

        var turn2 = conversationService.chat("u-001", "conv-faq-focus", "¿cuánto tarda un reembolso?");
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("reembolso");

        var turn3 = conversationService.chat("u-001", "conv-faq-focus", "¿y ha salido ya?");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("DELIVERED").contains("MRW");
    }

    @Test
    @DisplayName("Small talk: the default path presents capabilities without touching data")
    void smalltalkFallsBackToCapabilities() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-hi", "Hola, ¿qué tal?");

        assertThat(turn.intent()).isEqualTo("OTHER");
        assertThat(turn.resolved()).isFalse();
        assertThat(turn.reply()).contains("capacidades");
    }

    @Test
    @DisplayName("Not cancellable: keeps the focus so the next turn stays on that order")
    void nonCancellableKeepsFocus() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 201L, "NONE", "NONE"))

                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-keepfocus", "cancela el 201");
        assertThat(turn1.resolved()).isTrue();
        assertThat(turn1.reply()).contains("ya no puede cancelarse");

        var turn2 = conversationService.chat("u-002", "conv-keepfocus", "¿y el envío?");
        assertThat(turn2.resolved()).isTrue();

        assertThat(turn2.reply()).contains("SHIPPED").contains("Correos");
    }

    @Test
    @DisplayName("Chatty: small talk is answered without touching the focus or the business state")
    void chattyDoesNotTouchBusiness() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CHATTY", null, "NONE", "NONE"))
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-chatty", "estado del 789");
        assertThat(turn1.resolved()).isTrue();

        var turn2 = conversationService.chat("u-001", "conv-chatty", "oye, ¿cómo te llamas?");
        assertThat(turn2.intent()).isEqualTo("CHATTY");
        assertThat(turn2.reply()).contains("charla informal");

        var turn3 = conversationService.chat("u-001", "conv-chatty", "¿ha llegado ya?");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("DELIVERED").contains("MRW");
    }

    @Test
    @DisplayName("Ambiguity with focus: clarifies instead of dumping the menu, and escalates when repeated")
    void ambiguousWithFocusClarifiesThenEscalates() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"))
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-clarify", "estado del 789");
        assertThat(turn1.resolved()).isTrue();

        var turn2 = conversationService.chat("u-001", "conv-clarify", "ya pero no sé, a ver");
        assertThat(turn2.intent()).isEqualTo("OTHER");

        assertThat(turn2.reply()).contains("poco claro");
        assertThat(turn2.reply()).doesNotContain("fuera de las gestiones");

        var turn3 = conversationService.chat("u-001", "conv-clarify", "no sé, lo que sea");
        assertThat(turn3.reply()).contains("menú principal");
    }

    @Test
    @DisplayName("Return with human-in-the-loop: proposes, shows the button and only starts the return after confirmation")
    void returnRequiresConfirmationViaButton() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("RETURN", 789L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-return", "quiero devolver el monitor 789");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("admite devolución");

        assertThat(turn1.confirm()).isNotNull();
        assertThat(turn1.confirm().orderId()).isEqualTo(789L);
        assertThat(turn1.confirm().confirmPayload()).isEqualTo(UiAction.confirmReturn(789L));
        assertThat(turn1.confirm().cancelPayload()).isEqualTo(UiAction.abortReturn(789L));

        assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED);

        var turn2 = conversationService.chat("u-001", "conv-return", UiAction.confirmReturn(789L));
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.confirm()).isNull();

        assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(turn2.returns()).hasSize(1);
        assertThat(turn2.returns().get(0).id()).isEqualTo(789L);
        assertThat(turn2.returns().get(0).code()).isEqualTo("RET-0789");
        assertThat(turn2.reply()).contains("RET-0789");
    }

    @Test
    @DisplayName("Return dismissed by button: the user does not return anything and nothing changes")
    void abortingReturnChangesNothing() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("RETURN", 789L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-return-abort", "devolver el 789");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.confirm()).isNotNull();

        var turn2 = conversationService.chat("u-001", "conv-return-abort", UiAction.abortReturn(789L));
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("descartada");
        assertThat(turn2.returns()).isEmpty();
        assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("Return of an order not yet delivered: rejected by business code")
    void returnOfNonDeliveredOrderIsRejected() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("RETURN", 123L, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-return-ko", "quiero devolver el 123");

        assertThat(turn.resolved()).isTrue();
        assertThat(turn.returns()).isEmpty();
        assertThat(turn.confirm()).isNull();

        assertThat(turn.reply()).contains("ENTREGADO");
        assertThat(orderRepository.findById(123L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.IN_DELIVERY);
    }

    @Test
    @DisplayName("Cancelling an already delivered order redirects to a return (not to a ticket)")
    void cancelOfDeliveredOrderRedirectsToReturn() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 789L, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-cancel-delivered", "cancela el 789");

        assertThat(turn.reply()).contains("DEVOLUCIÓN");
        assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("Return already started: asking for the label re-sends the link card without starting anything new")
    void alreadyReturningReoffersLabel() {
        var order = orderRepository.findById(789L).orElseThrow();
        order.setStatus(OrderStatus.RETURN_REQUESTED);
        orderRepository.save(order);

        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("RETURN", 789L, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-return-label",
                "enséñame la etiqueta de devolución del 789");

        assertThat(turn.returns()).hasSize(1);
        assertThat(turn.returns().get(0).id()).isEqualTo(789L);
        assertThat(turn.returns().get(0).code()).isEqualTo("RET-0789");
        assertThat(turn.reply()).contains("etiqueta");

        assertThat(turn.confirm()).isNull();
        assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.RETURN_REQUESTED);
    }

    @Test
    @DisplayName("Tracking an order under return: shows the label, not the shipment card or the date")
    void trackingOrderInReturnShowsLabel() {
        var order = orderRepository.findById(789L).orElseThrow();
        order.setStatus(OrderStatus.RETURN_REQUESTED);
        orderRepository.save(order);

        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-track-return", "¿dónde está el 789?");

        assertThat(turn.returns()).hasSize(1);
        assertThat(turn.returns().get(0).code()).isEqualTo("RET-0789");
        assertThat(turn.orders()).isEmpty();

        assertThat(turn.reply()).contains("etiqueta");
        assertThat(turn.reply()).doesNotContain("entrega estimada");
    }
}
