package io.github.mikel0ne.autoops.k8s;

import io.github.mikel0ne.autoops.k8s.model.PodDescription;
import io.github.mikel0ne.autoops.k8s.model.PodDiagnosis;
import io.github.mikel0ne.autoops.k8s.model.PodStatus;
import io.github.mikel0ne.autoops.k8s.model.WorkloadStatus;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inspección read-only del clúster (fabric8): produce los "dossiers" estructurados que consumen las tools
 * de Kubernetes (doc 03 §2, doc 05 §1). La lógica de mapeo (modelo K8s → DTO) es {@code static} y pura para
 * poder testearla con objetos de fixture sin clúster real.
 *
 * <p>El cliente se inyecta de forma perezosa: el servidor MCP arranca aunque no haya clúster; el fallo (si
 * lo hay) ocurre al invocar una tool y se traduce a un mensaje accionable.
 */
@Service
public class K8sInspectionService {

    private static final Logger log = LoggerFactory.getLogger(K8sInspectionService.class);
    private static final int LOG_LINES_EN_DOSSIER = 50;

    private final ObjectProvider<KubernetesClient> clientProvider;

    public K8sInspectionService(ObjectProvider<KubernetesClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    private KubernetesClient client() {
        return clientProvider.getObject();
    }

    // ------------------------------------------------------------ diagnosticar_pod

    public PodDiagnosis diagnose(String namespace, String nombre) {
        Pod pod = client().pods().inNamespace(namespace).withName(nombre).get();
        if (pod == null) {
            throw new IllegalArgumentException("Pod no encontrado: " + namespace + "/" + nombre);
        }
        List<PodDiagnosis.Evento> eventos = events(namespace, nombre);
        String logs = safeLogs(namespace, nombre, null, LOG_LINES_EN_DOSSIER, false);
        return toDiagnosis(pod, eventos, logs);
    }

    static PodDiagnosis toDiagnosis(Pod pod, List<PodDiagnosis.Evento> eventos, String logs) {
        String fase = fase(pod);
        boolean ready = isReady(pod);

        List<PodDiagnosis.Condicion> condiciones = new ArrayList<>();
        for (PodCondition c : conditions(pod)) {
            condiciones.add(new PodDiagnosis.Condicion(c.getType(), c.getStatus(), c.getReason()));
        }

        List<PodDiagnosis.Contenedor> contenedores = new ArrayList<>();
        for (ContainerStatus cs : containerStatuses(pod)) {
            String estado = estadoContenedor(cs);
            contenedores.add(new PodDiagnosis.Contenedor(
                    cs.getName(),
                    cs.getImage(),
                    cs.getRestartCount() == null ? 0 : cs.getRestartCount(),
                    estado,
                    motivoEstado(cs)));
        }

        return new PodDiagnosis(fase, ready, motivoNoReady(pod), condiciones, contenedores, eventos, logs);
    }

    // ------------------------------------------------------------ estado_pods

    public List<PodStatus> pods(String namespace, String selector) {
        var ops = client().pods().inNamespace(namespace);
        List<Pod> items = (selector == null || selector.isBlank())
                ? ops.list().getItems()
                : ops.withLabelSelector(selector).list().getItems();
        return items.stream().map(K8sInspectionService::toPodStatus).toList();
    }

    static PodStatus toPodStatus(Pod pod) {
        int reinicios = containerStatuses(pod).stream()
                .mapToInt(cs -> cs.getRestartCount() == null ? 0 : cs.getRestartCount())
                .sum();
        return new PodStatus(name(pod), fase(pod), isReady(pod), reinicios, edad(pod));
    }

    // ------------------------------------------------------------ listar_workloads

    public List<WorkloadStatus> workloads(String namespace) {
        List<WorkloadStatus> out = new ArrayList<>();
        var apps = client().apps();

        var deployments = (namespace == null || namespace.isBlank())
                ? apps.deployments().inAnyNamespace().list().getItems()
                : apps.deployments().inNamespace(namespace).list().getItems();
        deployments.forEach(d -> out.add(new WorkloadStatus("Deployment",
                d.getMetadata().getName(),
                nz(d.getSpec() == null ? null : d.getSpec().getReplicas()),
                nz(d.getStatus() == null ? null : d.getStatus().getAvailableReplicas()))));

        var statefulSets = (namespace == null || namespace.isBlank())
                ? apps.statefulSets().inAnyNamespace().list().getItems()
                : apps.statefulSets().inNamespace(namespace).list().getItems();
        statefulSets.forEach(s -> out.add(new WorkloadStatus("StatefulSet",
                s.getMetadata().getName(),
                nz(s.getSpec() == null ? null : s.getSpec().getReplicas()),
                nz(s.getStatus() == null ? null : s.getStatus().getReadyReplicas()))));

        var daemonSets = (namespace == null || namespace.isBlank())
                ? apps.daemonSets().inAnyNamespace().list().getItems()
                : apps.daemonSets().inNamespace(namespace).list().getItems();
        daemonSets.forEach(ds -> out.add(new WorkloadStatus("DaemonSet",
                ds.getMetadata().getName(),
                nz(ds.getStatus() == null ? null : ds.getStatus().getDesiredNumberScheduled()),
                nz(ds.getStatus() == null ? null : ds.getStatus().getNumberAvailable()))));

        return out;
    }

    // ------------------------------------------------------------ describir_pod

    public PodDescription describe(String namespace, String nombre) {
        Pod pod = client().pods().inNamespace(namespace).withName(nombre).get();
        if (pod == null) {
            throw new IllegalArgumentException("Pod no encontrado: " + namespace + "/" + nombre);
        }
        return toDescription(pod);
    }

    static PodDescription toDescription(Pod pod) {
        List<PodDescription.ContenedorSpec> contenedores = new ArrayList<>();
        if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            for (Container c : pod.getSpec().getContainers()) {
                Map<String, String> requests = recursos(c, true);
                Map<String, String> limits = recursos(c, false);
                contenedores.add(new PodDescription.ContenedorSpec(c.getName(), c.getImage(), requests, limits));
            }
        }
        List<PodDiagnosis.Condicion> condiciones = conditions(pod).stream()
                .map(c -> new PodDiagnosis.Condicion(c.getType(), c.getStatus(), c.getReason()))
                .toList();
        String nodo = pod.getSpec() == null ? null : pod.getSpec().getNodeName();
        return new PodDescription(name(pod), namespace(pod), fase(pod), nodo, contenedores, condiciones);
    }

    // ------------------------------------------------------------ eventos

    public List<PodDiagnosis.Evento> events(String namespace, String objeto) {
        List<Event> events = client().v1().events().inNamespace(namespace).list().getItems();
        return events.stream()
                .filter(e -> objeto == null || objeto.isBlank()
                        || (e.getInvolvedObject() != null && objeto.equals(e.getInvolvedObject().getName())))
                .sorted(Comparator.comparing(K8sInspectionService::eventInstant,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(K8sInspectionService::toEvento)
                .toList();
    }

    static PodDiagnosis.Evento toEvento(Event e) {
        return new PodDiagnosis.Evento(e.getType(), e.getReason(), e.getMessage(), hace(eventInstant(e)));
    }

    // ------------------------------------------------------------ logs_pod

    public String logs(String namespace, String nombre, String contenedor, int lineas, boolean previous) {
        return safeLogs(namespace, nombre, contenedor, lineas, previous);
    }

    private String safeLogs(String namespace, String nombre, String contenedor, int lineas, boolean previous) {
        try {
            var pod = client().pods().inNamespace(namespace).withName(nombre);
            var base = (contenedor == null || contenedor.isBlank()) ? pod : pod.inContainer(contenedor);
            return previous
                    ? base.terminated().tailingLines(lineas).getLog()
                    : base.tailingLines(lineas).getLog();
        } catch (RuntimeException ex) {
            log.debug("No se pudieron obtener logs de {}/{}: {}", namespace, nombre, ex.getMessage());
            return "(sin logs disponibles: " + ex.getMessage() + ")";
        }
    }

    // ------------------------------------------------------------ helpers de mapeo

    private static String name(Pod pod) {
        return pod.getMetadata() == null ? null : pod.getMetadata().getName();
    }

    private static String namespace(Pod pod) {
        return pod.getMetadata() == null ? null : pod.getMetadata().getNamespace();
    }

    private static String fase(Pod pod) {
        return pod.getStatus() == null ? "Unknown" : pod.getStatus().getPhase();
    }

    private static List<PodCondition> conditions(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return List.of();
        }
        return pod.getStatus().getConditions();
    }

    private static List<ContainerStatus> containerStatuses(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return List.of();
        }
        return pod.getStatus().getContainerStatuses();
    }

    private static boolean isReady(Pod pod) {
        return conditions(pod).stream()
                .filter(c -> "Ready".equals(c.getType()))
                .anyMatch(c -> "True".equals(c.getStatus()));
    }

    /** Motivo accionable de por qué un pod no está Ready: reason de un contenedor en espera, o de la condición Ready. */
    private static String motivoNoReady(Pod pod) {
        if (isReady(pod)) {
            return null;
        }
        Optional<String> waiting = containerStatuses(pod).stream()
                .filter(cs -> cs.getState() != null && cs.getState().getWaiting() != null)
                .map(cs -> cs.getState().getWaiting().getReason())
                .filter(r -> r != null && !r.isBlank())
                .findFirst();
        if (waiting.isPresent()) {
            return waiting.get();
        }
        return conditions(pod).stream()
                .filter(c -> "Ready".equals(c.getType()))
                .map(PodCondition::getReason)
                .filter(r -> r != null && !r.isBlank())
                .findFirst()
                .orElse(fase(pod));
    }

    private static String estadoContenedor(ContainerStatus cs) {
        if (cs.getState() == null) {
            return "Unknown";
        }
        if (cs.getState().getRunning() != null) {
            return "Running";
        }
        if (cs.getState().getWaiting() != null) {
            return "Waiting";
        }
        if (cs.getState().getTerminated() != null) {
            return "Terminated";
        }
        return "Unknown";
    }

    private static String motivoEstado(ContainerStatus cs) {
        if (cs.getState() == null) {
            return null;
        }
        if (cs.getState().getWaiting() != null) {
            return cs.getState().getWaiting().getReason();
        }
        if (cs.getState().getTerminated() != null) {
            return cs.getState().getTerminated().getReason();
        }
        return null;
    }

    private static Map<String, String> recursos(Container c, boolean requests) {
        Map<String, String> out = new LinkedHashMap<>();
        if (c.getResources() == null) {
            return out;
        }
        Map<String, Quantity> source = requests ? c.getResources().getRequests() : c.getResources().getLimits();
        if (source != null) {
            source.forEach((k, v) -> out.put(k, v == null ? null : v.toString()));
        }
        return out;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String edad(Pod pod) {
        if (pod.getMetadata() == null || pod.getMetadata().getCreationTimestamp() == null) {
            return "?";
        }
        return hace(parseInstant(pod.getMetadata().getCreationTimestamp()));
    }

    private static Instant eventInstant(Event e) {
        if (e.getLastTimestamp() != null) {
            return parseInstant(e.getLastTimestamp());
        }
        if (e.getFirstTimestamp() != null) {
            return parseInstant(e.getFirstTimestamp());
        }
        return null;
    }

    private static Instant parseInstant(String rfc3339) {
        try {
            return Instant.parse(rfc3339);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Antigüedad legible desde un instante hasta ahora (p. ej. {@code 30s}, {@code 5m}, {@code 3h}, {@code 2d}). */
    static String hace(Instant instant) {
        if (instant == null) {
            return "?";
        }
        long segundos = Math.max(0, Duration.between(instant, Instant.now()).getSeconds());
        if (segundos < 60) {
            return segundos + "s";
        }
        if (segundos < 3600) {
            return (segundos / 60) + "m";
        }
        if (segundos < 86400) {
            return (segundos / 3600) + "h";
        }
        return (segundos / 86400) + "d";
    }
}
