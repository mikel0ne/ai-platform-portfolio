package io.github.mikel0ne.l1copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Memoria conversacional que se inyecta en los prompts del LLM. El estado del
 * grafo ya acumula el historial ({@code SupportState.history}: "USER: ..."/
 * "AGENT: ..."), pero por defecto NO se le pasaba al modelo: el clasificador y el
 * redactor solo veían el mensaje del turno actual más el estado destilado (foco,
 * intención, awaiting). El efecto era un agente que "no recuerda" lo que se dijo
 * antes si no quedó capturado en ese estado.
 * <p>
 * Estas propiedades activan, de forma <b>independiente</b> para el clasificador
 * (router) y para el redactor (respond), una ventana con los últimos N mensajes
 * del historial. Así ambos pueden resolver referencias ("eso", "lo de antes") y
 * mantener el hilo, sin volcar toda la conversación (control de tokens).
 *
 * <pre>
 * copilot:
 *   history:
 *     classifier: { enabled: true, size: 6 }
 *     responder:  { enabled: true, size: 6 }
 * </pre>
 */
@ConfigurationProperties(prefix = "copilot.history")
public record ConversationHistoryProperties(Window classifier, Window responder) {

    /** Tamaño de ventana por defecto: 6 mensajes (~3 turnos USER/AGENT). */
    private static final int DEFAULT_SIZE = 6;

    public ConversationHistoryProperties {
        if (classifier == null) {
            classifier = new Window(true, DEFAULT_SIZE);
        }
        if (responder == null) {
            responder = new Window(true, DEFAULT_SIZE);
        }
    }

    /**
     * Ventana de historial para un consumidor concreto. {@code enabled=false} lo
     * desactiva por completo (el prompt recibe "(sin historial previo)"); {@code size}
     * limita cuántos mensajes recientes se incluyen.
     */
    public record Window(Boolean enabled, Integer size) {

        private static final String NONE = "(sin historial previo)";

        public Window {
            if (enabled == null) {
                enabled = true;
            }
            if (size == null || size < 0) {
                size = DEFAULT_SIZE;
            }
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        /**
         * Renderiza los últimos {@code size} mensajes del historial como texto para
         * el prompt. Excluye el último elemento, que es el "USER: &lt;mensaje actual&gt;":
         * ese ya viaja aparte en el prompt y no debe duplicarse aquí.
         *
         * @param history historial acumulado del estado ("USER: ..."/"AGENT: ...")
         * @return los mensajes recientes unidos por saltos de línea, o un marcador
         *         si está desactivado o no hay nada previo
         */
        public String render(List<String> history) {
            if (!isEnabled() || history == null || history.size() <= 1) {
                return NONE;
            }
            // El último es el mensaje del turno actual; el contexto son los anteriores.
            List<String> previous = history.subList(0, history.size() - 1);
            int from = Math.max(0, previous.size() - size);
            return String.join("\n", previous.subList(from, previous.size()));
        }
    }
}

