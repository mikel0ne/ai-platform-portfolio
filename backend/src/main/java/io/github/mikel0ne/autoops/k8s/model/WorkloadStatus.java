package io.github.mikel0ne.autoops.k8s.model;

/**
 * Estado de un workload para la tool {@code listar_workloads} (doc 05 §1): réplicas deseadas vs
 * disponibles, la señal más directa de un despliegue que no converge.
 *
 * @param tipo        Deployment | StatefulSet | DaemonSet
 * @param nombre      nombre del workload
 * @param deseadas    réplicas deseadas
 * @param disponibles réplicas disponibles/listas
 */
public record WorkloadStatus(String tipo, String nombre, int deseadas, int disponibles) {}
