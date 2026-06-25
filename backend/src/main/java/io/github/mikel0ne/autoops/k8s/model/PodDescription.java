package io.github.mikel0ne.autoops.k8s.model;

import java.util.List;
import java.util.Map;

/**
 * Detalle de un pod al estilo {@code kubectl describe} para la tool {@code describir_pod} (doc 05 §1):
 * contenedores con imagen y requests/limits, además de las condiciones del pod.
 */
public record PodDescription(
        String nombre,
        String namespace,
        String fase,
        String nodo,
        List<ContenedorSpec> contenedores,
        List<PodDiagnosis.Condicion> condiciones) {

    /** Spec de un contenedor: imagen y recursos declarados. */
    public record ContenedorSpec(String nombre, String imagen, Map<String, String> requests, Map<String, String> limits) {}
}
