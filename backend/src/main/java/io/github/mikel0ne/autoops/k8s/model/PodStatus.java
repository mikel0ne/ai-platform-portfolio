package io.github.mikel0ne.autoops.k8s.model;

/**
 * Estado resumido de un pod para la tool {@code estado_pods} (doc 05 §1).
 *
 * @param nombre    nombre del pod
 * @param fase      fase del pod (Running, Pending, Failed…)
 * @param ready     {@code true} si todos los contenedores están Ready
 * @param reinicios reinicios totales de sus contenedores
 * @param edad      antigüedad legible (p. ej. {@code 3h}, {@code 2d})
 */
public record PodStatus(String nombre, String fase, boolean ready, int reinicios, String edad) {}
