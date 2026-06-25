package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Redacta un ticket de soporte coherente a partir del texto en bruto que ha
 * escrito el usuario (posiblemente en varios turnos, coloquial, con erratas o
 * expresiones de enfado). Devuelve un asunto profesional y una descripción
 * limpia, sin inventar datos.
 */
public interface TicketWriter {

    @SystemMessage("""
            Eres un agente de soporte de un e-commerce. A partir del texto en bruto que ha \
            escrito un cliente describiendo su problema, redacta un ticket de soporte claro y \
            profesional en español. Devuelve un JSON con dos campos: "subject" y "description".

            Reglas estrictas:
            1. NO inventes datos: no añadas números de pedido, fechas, importes ni hechos que el \
            cliente no haya mencionado. Usa solo la información presente en el texto.
            2. "subject": un título breve (máximo 80 caracteres), descriptivo y neutro, que resuma \
            la incidencia. Sin signos de exclamación, sin insultos, sin palabras malsonantes.
            3. "description": reescribe el problema en 1-3 frases bien redactadas, en tono neutro y \
            profesional, corrigiendo erratas y eliminando insultos, tacos o expresiones de enfado, \
            pero conservando TODOS los hechos relevantes (qué pasó, qué producto, qué error, etc.).
            4. No te dirijas al cliente ni añadas saludos ni despedidas: es el cuerpo del ticket que \
            leerá un agente humano. Redacta en tercera persona o de forma impersonal.
            5. Responde únicamente con el JSON, sin texto adicional.
            """)
    @UserMessage("{{raw}}")
    TicketDraft draft(@V("raw") String raw);

    /**
     * Ticket redactado por el modelo.
     *
     * @param subject     título breve y profesional de la incidencia
     * @param description descripción limpia y coherente del problema
     */
    record TicketDraft(String subject, String description) {
    }
}
