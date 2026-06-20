package io.github.mikel0ne.l1copilot.agent;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.lang.reflect.Method;
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
 *   <li>{@code treatment} — registro tú/usted del usuario (lo siembra el servicio por turno)</li>
 *   <li>{@code userMessage} — último mensaje del usuario (este turno)</li>
 *   <li>{@code history} — historial acumulado "USER: ..."/"AGENT: ..." (canal appender)</li>
 *   <li>{@code intent} — intención vigente ({@link Intent})</li>
 *   <li>{@code suspendedIntent} — intención de la gestión que quedó en pausa al atender
 *   una consulta FAQ lateral (digresión); se restaura al retomar la gestión</li>
 *   <li>{@code orderId} — pedido sobre el que se trabaja, si ya está resuelto</li>
 *   <li>{@code candidates} — pedidos ofrecidos al usuario para desambiguar
 *   (se guardan como texto plano para sobrevivir a reinicios con devtools)</li>
 *   <li>{@code awaiting} — pregunta pendiente del turno anterior ({@link Awaiting})</li>
 *   <li>{@code cancellable} — resultado de la última comprobación de cancelación</li>
 *   <li>{@code pendingCancelId} — pedido a la espera de que el usuario confirme su
 *   cancelación (dispara el botón de confirmación en la UI); efímero por turno</li>
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

    /** Registro (tú/usted) con el que dirigirse al usuario este turno. */
    public Treatment treatment() {
        return Treatment.fromState(this.value("treatment", Treatment.TU.name()));
    }

    public List<String> history() {
        return this.value("history", List::of);
    }

    public Intent intent() {
        return Intent.fromLlm(this.value("intent", Intent.NONE.name()));
    }

    /**
     * Intención de la gestión suspendida por una digresión FAQ, si la hay.
     * Vacío cuando no hay ninguna gestión en pausa (caso normal).
     */
    public Optional<Intent> suspendedIntent() {
        return this.<String>value("suspendedIntent").map(Intent::fromLlm);
    }

    public Optional<Long> orderId() {
        return this.value("orderId");
    }

    public List<String> candidates() {
        return this.value("candidates", List::of).stream()
                .map(SupportState::candidateLabel)
                .toList();
    }

    /**
     * Pedido que el router PROPONE como nuevo foco este turno, todavía SIN comprometer.
     * El router no toca el foco real ({@code orderId}) de una acción fresca: lo deja aquí y
     * es {@code verify} quien lo asciende a foco SOLO si aprueba la acción. Así nunca se
     * cambia de pedido para un camino que luego se descarta. Efímero: se reinicia cada turno.
     */
    public Optional<Long> proposedOrderId() {
        return this.<Number>value("proposedOrderId")
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    public Awaiting awaiting() {
        return Awaiting.fromState(this.value("awaiting", Awaiting.NOTHING.name()));
    }

    public boolean cancellable() {
        return this.value("cancellable", false);
    }

    /** Resultado de la última comprobación de devolución (¿el pedido admite devolución?). */
    public boolean returnable() {
        return this.value("returnable", false);
    }

    /**
     * ¿La incidencia (SUPPORT) que se está gestionando es sobre un pedido concreto? Lo fija el
     * router desde la decisión del clasificador. Cuando es true y aún no hay foco, el grafo
     * identifica el pedido (vía {@code resolve_order}) antes de abrir el ticket; cuando es false,
     * la incidencia es genérica (sin pedido asociado). Efímero por turno; solo importa en SUPPORT.
     */
    public boolean orderRelated() {
        return this.value("orderRelated", false);
    }

    /**
     * Veredicto del nodo verify sobre la decisión del router (SURE/DOUBT/REJECT).
     * Lo fija verify cada vez que corre; por defecto SURE para no bloquear un flujo
     * que no pasó por verify.
     */
    public Verdict verdict() {
        return Verdict.fromLlm(this.value("verdict", Verdict.SURE.name()));
    }

    /**
     * El turno avanza con una decisión solo "plausible" (verify devolvió DOUBT en una
     * lectura): hay que suavizar el tono de la pregunta hacia un ofrecimiento, no una
     * afirmación. Efímero por turno (lo reinicia el servicio).
     */
    public boolean tentative() {
        return this.value("tentative", false);
    }

    /**
     * El turno es una navegación explícita del usuario (BACK_TO_LIST / BACK_TO_MENU):
     * soltar el foco y volver al listado o al menú. NO es una acción de negocio fresca,
     * así que NO debe re-validarse en {@code verify} (el verificador re-leería el "volver
     * atrás" como si fuera una consulta y lo rechazaría). Efímero por turno: lo reinicia
     * el servicio y solo lo activa {@code RouterNode} al detectar la navegación.
     */
    public boolean navigating() {
        return this.value("navigating", false);
    }

    /**
     * Turnos consecutivos en que el usuario ha dicho algo poco claro (OTHER) teniendo
     * una gestión en curso. Gobierna la escalada del modo aclaración: a partir de 2 se
     * sugiere volver al menú principal. Se reinicia en cuanto el turno es claro.
     */
    public int clarifyAttempts() {
        return this.<Number>value("clarifyAttempts").map(Number::intValue).orElse(0);
    }

    /**
     * Pedido cuya cancelación está pendiente de confirmación por el usuario este turno.
     * Vacío salvo cuando {@code cancel_check} acaba de pedir confirmación: dispara el
     * botón de confirmación en la interfaz. Es efímero (se reinicia cada turno).
     */
    public Optional<Long> pendingCancelId() {
        return this.<Number>value("pendingCancelId")
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    /**
     * Pedido cuya devolución está pendiente de confirmación por el usuario este turno.
     * Vacío salvo cuando {@code return_check} acaba de pedir confirmación: dispara el
     * botón de confirmación en la interfaz. Es efímero (se reinicia cada turno). Es el
     * equivalente, para devoluciones, de {@link #pendingCancelId()}.
     */
    public Optional<Long> pendingReturnId() {
        return this.<Number>value("pendingReturnId")
                .map(Number::longValue)
                .filter(id -> id > 0);
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

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orderCards() {
        return this.value("orderCards", List::of);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ticketCards() {
        return this.value("ticketCards", List::of);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> returnCards() {
        return this.value("returnCards", List::of);
    }

    private static String candidateLabel(Object candidate) {
        if (candidate instanceof String label) {
            return label;
        }

        try {
            Method id = candidate.getClass().getMethod("id");
            Method description = candidate.getClass().getMethod("description");
            Method status = candidate.getClass().getMethod("status");
            StringBuilder label = new StringBuilder("#")
                    .append(id.invoke(candidate))
                    .append(" ")
                    .append(description.invoke(candidate))
                    .append(" (")
                    .append(status.invoke(candidate));

            try {
                Method orderDate = candidate.getClass().getMethod("orderDate");
                Object date = orderDate.invoke(candidate);
                if (date != null) {
                    label.append(", pedido el ").append(date);
                }
            } catch (NoSuchMethodException ignored) {
                // Some legacy candidates may not expose the date; status + description is enough.
            }

            return label.append(")").toString();
        } catch (ReflectiveOperationException e) {
            return String.valueOf(candidate);
        }
    }
}
