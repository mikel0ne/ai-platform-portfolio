package io.github.mikel0ne.l1copilot.agent;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import io.github.mikel0ne.l1copilot.agent.llm.Responder;
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
import static org.mockito.Mockito.when;

/**
 * Flujo completo del grafo (router → skills → respond) con el LLM sustituido
 * por fakes deterministas. Verifica los ciclos conversacionales reales:
 * el estado se restaura entre turnos vía checkpointer con el mismo threadId.
 * <p>
 * El Responder fake devuelve "ECHO[toolResult]": así cada aserción comprueba
 * qué información llegó realmente al nodo de redacción.
 */
@SpringBootTest(properties = "langchain4j.gemini.api-key=test-key")
class SupportAgentFlowTest {

    /** Se mockea también el modelo base: el test es hermético (cero red). */
    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private IntentClassifier classifier;

    @MockitoBean
    private Responder responder;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @BeforeEach
    void echoResponder() {
        when(responder.write(any(), any(), any(), any()))
                .thenAnswer(inv -> "ECHO[" + inv.getArgument(2) + "]");
    }

    @Test
    @DisplayName("Escenario de referencia: ambigüedad → desambiguación proactiva → ciclo → tracking")
    void referenceScenario_trackingWithDisambiguation() {
        when(classifier.classify(any(), any(), any(), any()))
                // turno 1: intención clara, pedido desconocido
                .thenReturn(new RouterDecision("TRACKING", null, "NONE"))
                // turno 2: "el portátil" resuelto contra los candidatos del estado
                .thenReturn(new RouterDecision("TRACKING", 123L, "NONE"));

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
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 200L, "NONE"))
                .thenReturn(new RouterDecision("CANCEL", null, "YES"));

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
    @DisplayName("La cancelación de un pedido enviado la rechaza el código de negocio")
    void cancellationOfShippedOrderIsRejected() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(new RouterDecision("CANCEL", 201L, "NONE"));

        var turn = conversationService.chat("u-002", "conv-cancel-ko", "Cancela el pedido 201");

        assertThat(turn.resolved()).isTrue();
        assertThat(turn.reply()).contains("ya no puede cancelarse");
        assertThat(orderRepository.findById(201L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("Soporte: con descripción escueta pide detalle y crea el ticket al completarla")
    void supportCollectsDetailThenCreatesTicket() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE"))
                .thenReturn(new RouterDecision("SUPPORT", null, "NONE"));

        var turn1 = conversationService.chat("u-001", "conv-support", "No funciona");

        assertThat(turn1.resolved()).isFalse();
        assertThat(turn1.reply()).contains("más detalle");

        var turn2 = conversationService.chat("u-001", "conv-support",
                "El monitor que compré llegó con la pantalla agrietada por la esquina superior y no enciende");

        assertThat(turn2.resolved()).isTrue();
        assertThat(turn2.reply()).contains("Ticket de soporte #");

        var tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc("u-001");
        assertThat(tickets).isNotEmpty();
        // El ticket acumuló la conversación completa, no solo el último mensaje
        assertThat(tickets.get(0).getDescription()).contains("No funciona").contains("agrietada");
    }

    @Test
    @DisplayName("Smalltalk: el camino por defecto presenta capacidades sin tocar datos")
    void smalltalkFallsBackToCapabilities() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(new RouterDecision("OTHER", null, "NONE"));

        var turn = conversationService.chat("u-001", "conv-hi", "Hola, ¿qué tal?");

        assertThat(turn.intent()).isEqualTo("OTHER");
        assertThat(turn.resolved()).isFalse();
        assertThat(turn.reply()).contains("capacidades");
    }
}
