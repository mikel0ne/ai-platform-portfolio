package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.dto.tools.OrderSummary;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estado del agente de soporte. Viaja por los nodos del grafo y persiste
 * entre turnos vía checkpointer ({@code threadId} = id de conversación).
 * <p>
 * Todos los valores son tipos serializables simples (String, Long, Boolean,
 * records): así el checkpointer no necesita serializers a medida.
 * <p>
 * Claves y semántica:
 * <ul>
 *   <li>{@code userId} — usuario autenticado; lo fija la capa HTTP, jamás el LLM</li>
 *   <li>{@code userMessage} — último mensaje del usuario (este turno)</li>
 *   <li>{@code history} — historial acumulado "USER: ..."/"AGENT: ..." (canal appender)</li>
 *   <li>{@code intent} — intención vigente ({@link Intent})</li>
 *   <li>{@code orderId} — pedido sobre el que se trabaja, si ya está resuelto</li>
 *   <li>{@code candidates} — pedidos ofrecidos al usuario para desambiguar</li>
 *   <li>{@code awaiting} — pregunta pendiente del turno anterior ({@link Awaiting})</li>
 *   <li>{@code cancellable} — resultado de la última comprobación de cancelación</li>
 *   <li>{@code confirmation} — YES/NO/NONE detectado por el router este turno</li>
 *   <li>{@code ticketDraft} — descripción de incidencia acumulada entre turnos</li>
 *   <li>{@code toolResult} — resultado de la última tool; ÚNICA fuente de datos del nodo respond</li>
 *   <li>{@code reply} — respuesta final del turno</li>
 *   <li>{@code resolved} — el turno cerró la gestión (envío informado, pedido cancelado, ticket creado)</li>
 * </ul>
 */
public class SupportState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            "history", Channels.appender(ArrayList::new)
    );

    public SupportState(Map<String, Object> initData) {
        super(initData);
    }

    public String userId() {
        return this.<String>value("userId")
                .orElseThrow(() -> new IllegalStateException("userId ausente en el estado del grafo"));
    }

    public String userMessage() {
        return this.value("userMessage", "");
    }

    public List<String> history() {
        return this.value("history", List::of);
    }

    public Intent intent() {
        return Intent.fromLlm(this.value("intent", Intent.NONE.name()));
    }

    public Optional<Long> orderId() {
        return this.value("orderId");
    }

    public List<OrderSummary> candidates() {
        return this.value("candidates", List::of);
    }

    public Awaiting awaiting() {
        return Awaiting.fromState(this.value("awaiting", Awaiting.NOTHING.name()));
    }

    public boolean cancellable() {
        return this.value("cancellable", false);
    }

    /** Respuesta del usuario a una confirmación pendiente: YES, NO o NONE. */
    public String confirmation() {
        return this.value("confirmation", "NONE");
    }

    public String ticketDraft() {
        return this.value("ticketDraft", "");
    }

    public String toolResult() {
        return this.value("toolResult", "");
    }

    public String reply() {
        return this.value("reply", "");
    }

    public boolean resolved() {
        return this.value("resolved", false);
    }
}
