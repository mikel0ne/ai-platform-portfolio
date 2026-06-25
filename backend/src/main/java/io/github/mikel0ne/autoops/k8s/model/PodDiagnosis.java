package io.github.mikel0ne.autoops.k8s.model;

import java.util.List;

/**
 * Dossier de diagnóstico de un pod (doc 05 §3): fase, condiciones, motivo de no-Ready, contenedores con
 * reinicios, eventos recientes y cola de logs. <b>Una llamada, todo el contexto</b> que el cliente MCP
 * necesita para razonar sobre por qué falla un pod.
 */
public record PodDiagnosis(
        String fase,
        boolean ready,
        String motivo,
        List<Condicion> condiciones,
        List<Contenedor> contenedores,
        List<Evento> eventos,
        String logs) {

    public record Condicion(String tipo, String estado, String motivo) {}

    public record Contenedor(String nombre, String imagen, int reinicios, String estado, String motivoEstado) {}

    public record Evento(String tipo, String motivo, String mensaje, String hace) {}
}
