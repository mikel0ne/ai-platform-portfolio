package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Camino por defecto (intención OTHER): smalltalk y presentación de
 * capacidades. No toca base de datos ni LLM; la redacción la hace respond.
 */
@Component
@Slf4j
public class FallbackNode implements NodeAction<SupportState> {

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---FALLBACK---");
        return Map.of(
                "toolResult", "Mensaje fuera de las gestiones soportadas. Saludar si procede y "
                        + "explicar las capacidades: informar del estado de envíos, cancelar pedidos "
                        + "y abrir incidencias para un agente humano.",
                "awaiting", Awaiting.NOTHING.name());
    }
}
