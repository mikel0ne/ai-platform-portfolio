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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Flujo completo del grafo (router → skills → respond) con el LLM sustituido
 * por fakes deterministas. Verifica los ciclos conversacionales reales:
 * el estado se restaura entre turnos vía checkpointer con el mismo threadId.
 * <p>
 * El Responder fake devuelve "ECHO[toolResult]": así cada aserción comprueba
 * qué información llegó realmente al nodo de redacción.
 */
@SpringBootTest(properties = {
        "langchain4j.gemini.api-key=test-key",
        // El perfil activo por defecto es dev (que activa el registro de turnos);
        // en el test no queremos ni fichero ni tabla de por medio.
        "copilot.turn-log.file.enabled=false",
        "copilot.turn-log.db.enabled=false"})
class SupportAgentFlowTest {

    /** Se mockea también el modelo base: el test es hermético (cero red). */
    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private IntentClassifier classifier;

    @MockitoBean
    private Responder responder;

    /** Redactor de charla informal (modo chatty): mismo eco que el responder de negocio. */
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
        // El redactor de charla informal refleja igual el toolResult: las aserciones de chatty
        // comprueban qué llegó a redacción, no el tono real del modelo.
        when(chattyResponder.write(any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> "ECHO[" + inv.getArgument(2) + "]");
        // Por defecto el verificador da el visto bueno (SURE): los escenarios que prueban el
        // flujo de la gestión no se ven afectados. Los tests de DOUBT/REJECT lo sobreescriben.
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("SURE", "gestión clara"));
    }

    @Test
    @DisplayName("Escenario de referencia: ambigüedad → desambiguación proactiva → ciclo → tracking")
    void referenceScenario_trackingWithDisambiguation() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                // turno 1: intención clara, pedido desconocido
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"))
                // turno 2: "el portátil" resuelto contra los candidatos del estado
                .thenReturn(new RouterDecision("TRACKING", 123L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-ref", "¿Dónde está mi pedido?");

        assertThat(turn1.intent()).isEqualTo("TRACKING");
        assertThat(turn1.resolved()).isFalse();
        // Proactividad: en vez de pedir el id a ciegas, ofreció los pedidos recientes
        assertThat(turn1.reply()).contains("#123").contains("#456").contains("Portátil");

        var turn2 = conversationService.chat("u-001", "conv-ref", "El portátil");

        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("IN_DELIVERY").contains("SEUR");
    }

    @Test
    @DisplayName("Cancelación con human-in-the-loop: pide confirmación y solo ejecuta tras el sí")
    void cancellationRequiresConfirmation() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 200L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", null, "YES", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-cancel", "Cancela el pedido 200");

        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("puede cancelarse");
        // Nada se ha tocado todavía en base de datos
        assertThat(orderRepository.findById(200L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CREATED);

        var turn2 = conversationService.chat("u-002", "conv-cancel", "Sí, cancélalo");

        assertThat(turn2.resolved()).isTrue();
        assertThat(orderRepository.findById(200L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancelación por botón: el comando del botón ejecuta la cancelación sin pasar por el LLM")
    void cancellationViaButtonIsDeterministic() {
        // El router solo se consulta en el turno 1 (CANCEL del 202). En el turno 2, el comando
        // del botón se resuelve en el fast-path del router, sin tocar el clasificador.
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 202L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-cancel-btn", "quiero cancelar el 202");
        assertThat(turn1.resolved()).isFalse();
        // Se ofrece el botón de confirmación con sus payloads deterministas
        assertThat(turn1.confirm()).isNotNull();
        assertThat(turn1.confirm().orderId()).isEqualTo(202L);
        assertThat(turn1.confirm().confirmPayload()).isEqualTo(UiAction.confirmCancel(202L));
        assertThat(turn1.confirm().warning()).contains("no se puede deshacer");
        // u-002 (Luis, 70 años) → trato de usted: "¿Confirma...", no "¿Confirmas..."
        assertThat(turn1.confirm().title()).contains("¿Confirma la cancelación");
        assertThat(turn1.confirm().title()).doesNotContain("Confirmas");
        // Nada se ha tocado todavía en base de datos
        assertThat(orderRepository.findById(202L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        var turn2 = conversationService.chat("u-002", "conv-cancel-btn", UiAction.confirmCancel(202L));
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.confirm()).isNull();
        assertThat(orderRepository.findById(202L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Tras cancelar un pedido, el siguiente turno vuelve a ofrecer los pedidos restantes")
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
    @DisplayName("Anular la cancelación NO suelta el foco: el pedido sigue activo para el siguiente turno")
    void abortingCancellationKeepsFocus() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 456L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", null, "NO", "NONE"))
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-cancel-abort", "Cancela el pedido 456");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("puede cancelarse");

        // El usuario se echa atrás: NO cancela. El pedido sigue en su estado normal (PAID).
        var turn2 = conversationService.chat("u-001", "conv-cancel-abort", "No, consérvalo");
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("descartada");
        assertThat(orderRepository.findById(456L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        // Foco conservado: una consulta sin id (TRACKING) actúa sobre el #456, en lugar de
        // volver a ofrecer el menú de pedidos. El usuario "conservarlo" sigue sobre ese pedido.
        var turn3 = conversationService.chat("u-001", "conv-cancel-abort", "¿y dónde está?");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("#456").contains("Ratón");
        assertThat(turn3.reply()).doesNotContain("#123").doesNotContain("#789");
    }

    @Test
    @DisplayName("La cancelación de un pedido enviado la rechaza el código de negocio")
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
    @DisplayName("Soporte: propone la incidencia (no la crea), pide detalle y la crea al confirmarla")
    void supportProposesThenCreatesTicket() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));
        // El redactor LLM limpia el texto en bruto en un asunto y descripción coherentes
        when(ticketWriter.draft(any())).thenReturn(new TicketDraft(
                "Monitor recibido con la pantalla agrietada",
                "El monitor llegó con la pantalla agrietada en la esquina superior y no enciende."));

        var turn1 = conversationService.chat("u-001", "conv-support", "No funciona");

        // No se crea nada todavía: se propone abrir la incidencia y se pide detalle
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("CONFIRMAR").contains("detalle");
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-001", "conv-support",
                "El monitor que compré llegó con la pantalla agrietada por la esquina superior y no enciende");

        assertThat(turn2.resolved()).isTrue();
        // El nodo SUPPORT propaga el código del ticket (SUP-NNNN) al texto del turno
        assertThat(turn2.reply()).contains("Ticket de soporte").contains("SUP-");
        // Y entrega una tarjeta-enlace al ticket para que la UI la renderice
        assertThat(turn2.tickets()).hasSize(1);
        assertThat(turn2.tickets().get(0).code()).startsWith("SUP-");
        assertThat(turn2.tickets().get(0).id()).isNotNull();
        // El título de la tarjeta es el asunto redactado por el LLM, no el texto crudo
        assertThat(turn2.tickets().get(0).title()).isEqualTo("Monitor recibido con la pantalla agrietada");

        // El redactor recibió la conversación COMPLETA acumulada, no solo el último mensaje
        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketWriter).draft(rawCaptor.capture());
        assertThat(rawCaptor.getValue()).contains("No funciona").contains("agrietada");

        var tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc("u-001");
        assertThat(tickets).isNotEmpty();
        // Lo persistido es la redacción limpia del LLM, no el texto en bruto del usuario
        assertThat(tickets.get(0).getSubject()).isEqualTo("Monitor recibido con la pantalla agrietada");
        assertThat(tickets.get(0).getDescription())
                .doesNotContain("No funciona")
                .contains("agrietada");
    }

    @Test
    @DisplayName("Soporte: ni un primer mensaje largo y airado crea la incidencia de golpe; primero la propone")
    void supportDoesNotAutoCreateOnFirstLongMessage() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-support-long",
                "joven, yo hice la compra así que ya me dirán qué hacemos si ustedes no se acuerdan");

        // Mensaje largo pero sin un problema concreto: se propone, NO se crea nada
        assertThat(turn.resolved()).isFalse();
        assertThat(turn.tickets()).isEmpty();
        assertThat(turn.reply()).contains("CONFIRMAR");
    }

    @Test
    @DisplayName("Soporte: si el usuario rechaza la incidencia en el gate, no se crea nada y se reencamina")
    void supportDeclineAtGateCreatesNothingAndReroutes() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                // turno 1: parece una incidencia → propuesta (gate TICKET_CONFIRM)
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))
                // turno 2: el usuario rechaza y en realidad quiere otra cosa
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-support-no", "tengo un problema");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-001", "conv-support-no", "no, déjalo, mejor otra cosa");

        // El borrador se descarta: nunca se crea la incidencia y el turno cae en capacidades
        assertThat(turn2.tickets()).isEmpty();
        assertThat(turn2.reply()).contains("capacidades");
        // El redactor de tickets jamás se invocó
        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Soporte: si en el gate el usuario pide cancelar, se descarta la incidencia y va a cancelación")
    void supportPivotToCancelAtGateDropsTicket() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                // turno 1: parece incidencia → propuesta
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))
                // turno 2: en realidad quiere cancelar el pedido 200
                .thenReturn(new RouterDecision("CANCEL", 200L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-support-pivot", "esto va fatal");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-002", "conv-support-pivot", "no, mejor cancela el pedido 200");

        // Reencaminado a cancelación (human-in-the-loop): pide confirmación, sin crear incidencia
        assertThat(turn2.tickets()).isEmpty();
        assertThat(turn2.reply()).contains("puede cancelarse");
        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Verify con DOUBT en una acción: en vez de actuar, pregunta (clarify) y no crea nada")
    void verifyDoubtAsksInsteadOfActing() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));
        // El verificador no lo ve claro: el turno debe ir a clarify, no a crear el ticket
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("DOUBT", "queja vaga, sin problema concreto"));

        var turn = conversationService.chat("u-001", "conv-verify", "menudo desastre de tienda, de verdad");

        assertThat(turn.resolved()).isFalse();
        assertThat(turn.tickets()).isEmpty();
        // clarify deja una instrucción CLARIFY: para el Responder (aquí el ECHO la refleja)
        assertThat(turn.reply()).contains("CLARIFY");
        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Verify con REJECT en una acción: pregunta liderando con alternativas, sin sembrar el ticket")
    void verifyRejectLeadsWithAlternatives() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("REJECT", "es una pregunta general, no una incidencia"));

        var turn = conversationService.chat("u-001", "conv-reject", "¿y cómo van las devoluciones?");

        assertThat(turn.tickets()).isEmpty();
        // La instrucción de clarify lidera con alternativas (NO con la incidencia)
        assertThat(turn.reply()).contains("CLARIFY").contains("alternativas");
        verifyNoInteractions(ticketWriter);
    }

    @Test
    @DisplayName("Verify con DOUBT en una LECTURA: sigue adelante con tono tentativo (no frena)")
    void verifyDoubtOnReadProceedsTentatively() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("DOUBT", "no está claro si quiere el envío"));

        var turn = conversationService.chat("u-001", "conv-tentative", "a ver mis cosas");

        // Lectura inocua: NO frena, ofrece los pedidos (desambiguación), pero en tono tentativo
        assertThat(turn.reply()).contains("#123").contains("#456");
        verify(responder).write(any(), any(), any(), any(), anyBoolean(), any(), any(), eq(true), any());
    }

    @Test
    @DisplayName("Verify DOUBT → el usuario confirma la incidencia: se crea SIN volver a preguntar")
    void clarifyThenConfirmCreatesTicketWithoutReasking() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                // turno 1: el router cree SUPPORT pero verify duda → clarify
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"))
                // turno 2: el usuario confirma y describe; sigue siendo SUPPORT
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("DOUBT", "no está claro"));
        when(ticketWriter.draft(any())).thenReturn(new TicketDraft(
                "Monitor que no enciende", "El monitor recibido no enciende y llegó dañado."));

        var turn1 = conversationService.chat("u-001", "conv-clar", "tengo un lío con el monitor");
        assertThat(turn1.tickets()).isEmpty();

        var turn2 = conversationService.chat("u-001", "conv-clar",
                "sí, ábrela: el monitor llegó dañado y no enciende, quiero que lo revise alguien");

        // Se crea directamente: NO se vuelve a preguntar "¿quieres una incidencia?"
        assertThat(turn2.tickets()).hasSize(1);
        assertThat(turn2.reply()).contains("Ticket de soporte");
        // verify solo corrió en el turno fresco; el de confirmación no se re-verifica
        verify(verifier, times(1)).verify(any(), any(), any());
        // El ticket une el mensaje que disparó el tema con el detalle posterior
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(ticketWriter).draft(raw.capture());
        assertThat(raw.getValue()).contains("lío con el monitor").contains("no enciende");
    }

    @Test
    @DisplayName("Foco pegajoso: tras consultar un pedido, un mensaje sin id sigue siendo de ESE pedido (no salta)")
    void focusStaysOnOrderAcrossTurns() {
        // turno 1: consulta el #789. turnos 2-3: el usuario sigue hablando del mismo pedido
        // (reporta una incidencia) SIN nombrar ninguno: el router devuelve orderId=null y el
        // foco debe conservarse en 789 — nunca saltar a otro pedido del listado.
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

        // Mensaje SIN id: el flujo de incidencia propone abrirla y pide detalle, pero el foco sigue en 789
        var turn2 = conversationService.chat("u-001", "conv-focus", "no lo he recibido");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("detalle");
        assertThat(turn2.tickets()).isEmpty();

        var turn3 = conversationService.chat("u-001", "conv-focus",
                "El monitor Dell figura como entregado pero no lo he recibido en casa, llevo días esperándolo");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.tickets()).hasSize(1);

        // Prueba dura de que NO saltó: el ticket quedó asociado al pedido en foco (789), no a otro
        Long newTicketId = turn3.tickets().get(0).id();
        assertThat(ticketRepository.findById(newTicketId).orElseThrow().getOrderId())
                .isEqualTo(789L);
    }

    @Test
    @DisplayName("Volver al listado: con un pedido en foco, 'ver mis pedidos' suelta el foco y re-ofrece todos")
    void backToListReleasesFocusAndReListsOrders() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                // navigation=BACK_TO_LIST: da igual la intención que adivine el modelo
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        var turn1 = conversationService.chat("u-001", "conv-back", "Estado del pedido 789");
        assertThat(turn1.resolved()).isTrue();

        var turn2 = conversationService.chat("u-001", "conv-back", "enséñame todos mis pedidos");
        // Se vuelve al menú: re-ofrece los tres pedidos y queda a la espera de elección
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("#123").contains("#456").contains("#789");
    }

    @Test
    @DisplayName("Volver al menú principal: con un pedido en foco, vuelve a fallback sin re-listar pedidos")
    void backToMenuReleasesFocusAndShowsCapabilities() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                // navigation=BACK_TO_MENU: a diferencia de BACK_TO_LIST, cae siempre en OTHER
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "BACK_TO_MENU"));

        var turn1 = conversationService.chat("u-001", "conv-menu", "Estado del pedido 789");
        assertThat(turn1.resolved()).isTrue();

        var turn2 = conversationService.chat("u-001", "conv-menu", "volver al menú principal");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("dudas generales");
        assertThat(turn2.reply()).doesNotContain("#123").doesNotContain("#456").doesNotContain("#789");
    }

    @Test
    @DisplayName("No se actúa antes de validar: un CANCEL que verify rechaza NO llega a mover el foco")
    void rejectedActionNeverCommitsFocus() {
        // El foco está en #789. En el turno 2 el router cree que el usuario quiere cancelar OTRO
        // pedido (#123), pero NO toca el foco: lo deja propuesto. La validación (verify) lo rechaza,
        // así que el foco nunca se mueve (no se comete estado para un camino que se descarta).
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("CANCEL", 123L, "NONE", "NONE"))
                // turno 3: foco pegajoso (sin id) → debe seguir en #789, no en #123
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new Verifier.Decision("SURE", "consulta clara"))
                .thenReturn(new Verifier.Decision("REJECT", "no pide cancelar nada"))
                .thenReturn(new Verifier.Decision("SURE", "consulta clara"));

        var turn1 = conversationService.chat("u-001", "conv-defer", "Estado del pedido 789");
        assertThat(turn1.reply()).contains("DELIVERED").contains("MRW");

        // verify rechaza: no se ejecuta nada y se pregunta (clarify)
        var turn2 = conversationService.chat("u-001", "conv-defer", "cancela el 123 digo no sé");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("CLARIFY");

        // Prueba dura: sin nombrar pedido, el foco sigue en #789 (el #123 nunca se llegó a fijar)
        var turn3 = conversationService.chat("u-001", "conv-defer", "¿y por dónde va?");
        assertThat(turn3.reply()).contains("DELIVERED").contains("MRW");
        assertThat(turn3.reply()).doesNotContain("IN_DELIVERY").doesNotContain("SEUR");
    }

    @Test
    @DisplayName("FAQ: una pregunta general se responde con la base de conocimiento de la tienda")
    void faqAnswersFromKnowledgeBase() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("FAQ", null, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-faq", "¿Cuál es la política de devoluciones?");

        assertThat(turn.resolved()).isTrue();
        assertThat(turn.reply()).contains("devoluci");
    }

    @Test
    @DisplayName("Digresión: una FAQ a mitad de elegir pedido se responde y luego se retoma la elección")
    void faqDigressionDuringOrderChoiceResumes() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                // turno 1: tracking sin pedido → desambiguación (awaiting=ORDER_CHOICE)
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"))
                // turno 2: en vez de elegir, pregunta una FAQ (digresión)
                .thenReturn(new RouterDecision("FAQ", null, "NONE", "NONE"))
                // turno 3: ahora sí elige; el LLM ve TRACKING vigente y resuelve el pedido
                .thenReturn(new RouterDecision("TRACKING", 123L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-digress", "¿Dónde está mi pedido?");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("#123").contains("#456");

        // La FAQ se atiende, pero la gestión NO se cierra: sigue pendiente la elección
        var turn2 = conversationService.chat("u-001", "conv-digress", "oye, ¿y las devoluciones cómo van?");
        assertThat(turn2.resolved()).isFalse();
        assertThat(turn2.reply()).contains("devoluci");

        // El usuario retoma: el foco se resuelve y el tracking se completa con normalidad
        var turn3 = conversationService.chat("u-001", "conv-digress", "el portátil");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("IN_DELIVERY").contains("SEUR");
    }

    @Test
    @DisplayName("Foco pegajoso: una FAQ no roba el foco del pedido en curso")
    void faqKeepsOrderFocus() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                // turno 1: consulta el #789 (queda en foco, sin nada pendiente)
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                // turno 2: pregunta general; al no haber awaiting, es FAQ normal (no digresión)
                .thenReturn(new RouterDecision("FAQ", null, "NONE", "NONE"))
                // turno 3: vuelve al pedido sin nombrarlo → el foco debe seguir en 789
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-faq-focus", "Estado del pedido 789");
        assertThat(turn1.resolved()).isTrue();
        assertThat(turn1.reply()).contains("DELIVERED").contains("MRW");

        var turn2 = conversationService.chat("u-001", "conv-faq-focus", "¿cuánto tarda un reembolso?");
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("reembolso");

        // Sin nombrar pedido: el tracking debe seguir siendo del 789, no re-listar
        var turn3 = conversationService.chat("u-001", "conv-faq-focus", "¿y ha salido ya?");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("DELIVERED").contains("MRW");
    }

    @Test
    @DisplayName("Smalltalk: el camino por defecto presenta capacidades sin tocar datos")
    void smalltalkFallsBackToCapabilities() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-hi", "Hola, ¿qué tal?");

        assertThat(turn.intent()).isEqualTo("OTHER");
        assertThat(turn.resolved()).isFalse();
        assertThat(turn.reply()).contains("capacidades");
    }

    @Test
    @DisplayName("No cancelable: conserva el foco para que el siguiente turno siga sobre ese pedido")
    void nonCancellableKeepsFocus() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 201L, "NONE", "NONE"))
                // turno 2: sin nombrar pedido; el foco debe seguir en 201, no re-listar
                .thenReturn(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-002", "conv-keepfocus", "cancela el 201");
        assertThat(turn1.resolved()).isTrue();
        assertThat(turn1.reply()).contains("ya no puede cancelarse");

        var turn2 = conversationService.chat("u-002", "conv-keepfocus", "¿y el envío?");
        assertThat(turn2.resolved()).isTrue();
        // Tracking del MISMO pedido 201: prueba de que el foco no se soltó
        assertThat(turn2.reply()).contains("SHIPPED").contains("Correos");
    }

    @Test
    @DisplayName("Chatty: la charla informal se responde sin tocar el foco ni el negocio")
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

        // El foco siguió en 789 pese a la charla: el tracking sin id vuelve a ese pedido
        var turn3 = conversationService.chat("u-001", "conv-chatty", "¿ha llegado ya?");
        assertThat(turn3.resolved()).isTrue();
        assertThat(turn3.reply()).contains("DELIVERED").contains("MRW");
    }

    @Test
    @DisplayName("Ambigüedad con foco: aclara en vez de volcar el menú, y escala al repetirse")
    void ambiguousWithFocusClarifiesThenEscalates() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"))
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"))
                .thenReturn(new RouterDecision("OTHER", null, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-clarify", "estado del 789");
        assertThat(turn1.resolved()).isTrue();

        // 1ª ambigüedad con foco → modo aclaración, NO el menú de capacidades
        var turn2 = conversationService.chat("u-001", "conv-clarify", "ya pero no sé, a ver");
        assertThat(turn2.intent()).isEqualTo("OTHER");
        // Modo aclaración (sobre el pedido en foco), NO el menú de capacidades por defecto
        assertThat(turn2.reply()).contains("poco claro");
        assertThat(turn2.reply()).doesNotContain("fuera de las gestiones");

        // 2ª ambigüedad seguida → escala sugiriendo el menú principal
        var turn3 = conversationService.chat("u-001", "conv-clarify", "no sé, lo que sea");
        assertThat(turn3.reply()).contains("menú principal");
    }

    @Test
    @DisplayName("Devolución con human-in-the-loop: propone, muestra el botón y solo inicia la devolución tras confirmar")
    void returnRequiresConfirmationViaButton() {
        // #789 (Monitor Dell) de u-001 está ENTREGADO → admite devolución. El router solo se
        // consulta en el turno 1; el clic del botón se resuelve en el fast-path, sin tocar el LLM.
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("RETURN", 789L, "NONE", "NONE"));
        try {
            var turn1 = conversationService.chat("u-001", "conv-return", "quiero devolver el monitor 789");
            assertThat(turn1.resolved()).isFalse();
            assertThat(turn1.reply()).contains("admite devolución");
            // Se ofrece el botón de confirmación con sus payloads deterministas
            assertThat(turn1.confirm()).isNotNull();
            assertThat(turn1.confirm().orderId()).isEqualTo(789L);
            assertThat(turn1.confirm().confirmPayload()).isEqualTo(UiAction.confirmReturn(789L));
            assertThat(turn1.confirm().cancelPayload()).isEqualTo(UiAction.abortReturn(789L));
            // Nada se ha tocado todavía: el pedido sigue ENTREGADO
            assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DELIVERED);

            // El clic del botón inicia la devolución sin pasar por el LLM
            var turn2 = conversationService.chat("u-001", "conv-return", UiAction.confirmReturn(789L));
            assertThat(turn2.resolved()).isTrue();
            assertThat(turn2.confirm()).isNull();
            // El pedido pasa a RETURN_REQUESTED y se entrega la tarjeta-enlace a la etiqueta
            assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.RETURN_REQUESTED);
            assertThat(turn2.returns()).hasSize(1);
            assertThat(turn2.returns().get(0).id()).isEqualTo(789L);
            assertThat(turn2.returns().get(0).code()).isEqualTo("RET-0789");
            assertThat(turn2.reply()).contains("RET-0789");
        } finally {
            // Restaura el estado del pedido: el suite no re-siembra la BD entre tests y varios
            // escenarios asumen #789 como DELIVERED.
            var o = orderRepository.findById(789L).orElseThrow();
            o.setStatus(OrderStatus.DELIVERED);
            orderRepository.save(o);
        }
    }

    @Test
    @DisplayName("Devolución por botón de descarte: el usuario no devuelve y no se toca nada")
    void abortingReturnChangesNothing() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("RETURN", 789L, "NONE", "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-return-abort", "devolver el 789");
        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.confirm()).isNotNull();

        // El usuario descarta la devolución con el botón "No": nada cambia en la BD
        var turn2 = conversationService.chat("u-001", "conv-return-abort", UiAction.abortReturn(789L));
        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("descartada");
        assertThat(turn2.returns()).isEmpty();
        assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("Devolución de un pedido aún no entregado: la rechaza el código de negocio")
    void returnOfNonDeliveredOrderIsRejected() {
        // #123 (portátil) está IN_DELIVERY: todavía no se puede devolver
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("RETURN", 123L, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-return-ko", "quiero devolver el 123");

        assertThat(turn.resolved()).isTrue();
        assertThat(turn.returns()).isEmpty();
        assertThat(turn.confirm()).isNull();
        // Se explica que la devolución solo es posible una vez ENTREGADO; nada se ha tocado
        assertThat(turn.reply()).contains("ENTREGADO");
        assertThat(orderRepository.findById(123L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.IN_DELIVERY);
    }

    @Test
    @DisplayName("Cancelar un pedido ya entregado reconduce a la devolución (no a una incidencia)")
    void cancelOfDeliveredOrderRedirectsToReturn() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 789L, "NONE", "NONE"));

        var turn = conversationService.chat("u-001", "conv-cancel-delivered", "cancela el 789");

        // El nodo de cancelación reconduce hacia una DEVOLUCIÓN, sin tocar el pedido
        assertThat(turn.reply()).contains("DEVOLUCIÓN");
        assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("Devolución ya iniciada: pedir la etiqueta re-emite la tarjeta-enlace sin iniciar nada de nuevo")
    void alreadyReturningReoffersLabel() {
        // Dejamos #789 como una devolución ya en marcha
        var order = orderRepository.findById(789L).orElseThrow();
        order.setStatus(OrderStatus.RETURN_REQUESTED);
        orderRepository.save(order);
        try {
            when(classifier.classify(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new RouterDecision("RETURN", 789L, "NONE", "NONE"));

            var turn = conversationService.chat("u-001", "conv-return-label",
                    "enséñame la etiqueta de devolución del 789");

            // Se re-ofrece la etiqueta (tarjeta-enlace), sin volver a "iniciar" la devolución
            assertThat(turn.returns()).hasSize(1);
            assertThat(turn.returns().get(0).id()).isEqualTo(789L);
            assertThat(turn.returns().get(0).code()).isEqualTo("RET-0789");
            assertThat(turn.reply()).contains("etiqueta");
            // No hay botón de confirmación y el pedido sigue exactamente igual
            assertThat(turn.confirm()).isNull();
            assertThat(orderRepository.findById(789L).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.RETURN_REQUESTED);
        } finally {
            var o = orderRepository.findById(789L).orElseThrow();
            o.setStatus(OrderStatus.DELIVERED);
            orderRepository.save(o);
        }
    }

    @Test
    @DisplayName("Tracking de un pedido en devolución: muestra la etiqueta, no la ficha de envío ni la fecha")
    void trackingOrderInReturnShowsLabel() {
        // #789 está en devolución; consultarlo (tracking) debe surfacar la etiqueta, no el envío
        var order = orderRepository.findById(789L).orElseThrow();
        order.setStatus(OrderStatus.RETURN_REQUESTED);
        orderRepository.save(order);
        try {
            when(classifier.classify(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new RouterDecision("TRACKING", 789L, "NONE", "NONE"));

            var turn = conversationService.chat("u-001", "conv-track-return", "¿dónde está el 789?");

            // Aparece la tarjeta-enlace a la etiqueta, NO la ficha de pedido (orderCards)
            assertThat(turn.returns()).hasSize(1);
            assertThat(turn.returns().get(0).code()).isEqualTo("RET-0789");
            assertThat(turn.orders()).isEmpty();
            // El texto se centra en la etiqueta y NO en la fecha de entrega del envío original
            assertThat(turn.reply()).contains("etiqueta");
            assertThat(turn.reply()).doesNotContain("entrega estimada");
        } finally {
            var o = orderRepository.findById(789L).orElseThrow();
            o.setStatus(OrderStatus.DELIVERED);
            orderRepository.save(o);
        }
    }
}
