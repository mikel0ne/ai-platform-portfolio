package io.github.mikel0ne.autoops.k8s;

import io.github.mikel0ne.autoops.error.NotFoundException;
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

@Service
public class K8sInspectionService {

    private static final Logger log = LoggerFactory.getLogger(K8sInspectionService.class);
    private static final int DOSSIER_LOG_LINES = 50;

    private final ObjectProvider<KubernetesClient> clientProvider;

    public K8sInspectionService(ObjectProvider<KubernetesClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    private KubernetesClient client() {
        return clientProvider.getObject();
    }

    public PodDiagnosis diagnose(String namespace, String name) {
        Pod pod = client().pods().inNamespace(namespace).withName(name).get();
        if (pod == null) {
            throw new NotFoundException("Pod not found: " + namespace + "/" + name);
        }
        List<PodDiagnosis.EventInfo> events = events(namespace, name);
        String logs = safeLogs(namespace, name, null, DOSSIER_LOG_LINES, false);
        return toDiagnosis(pod, events, logs);
    }

    static PodDiagnosis toDiagnosis(Pod pod, List<PodDiagnosis.EventInfo> events, String logs) {
        String phase = phase(pod);
        boolean ready = isReady(pod);

        List<PodDiagnosis.ConditionInfo> conditions = new ArrayList<>();
        for (PodCondition c : conditions(pod)) {
            conditions.add(new PodDiagnosis.ConditionInfo(c.getType(), c.getStatus(), c.getReason()));
        }

        List<PodDiagnosis.ContainerInfo> containers = new ArrayList<>();
        for (ContainerStatus cs : containerStatuses(pod)) {
            String state = containerState(cs);
            containers.add(new PodDiagnosis.ContainerInfo(
                    cs.getName(),
                    cs.getImage(),
                    cs.getRestartCount() == null ? 0 : cs.getRestartCount(),
                    state,
                    stateReason(cs)));
        }

        return new PodDiagnosis(phase, ready, notReadyReason(pod), conditions, containers, events, logs);
    }

    public List<PodStatus> pods(String namespace, String selector) {
        var ops = client().pods().inNamespace(namespace);
        List<Pod> items = (selector == null || selector.isBlank())
                ? ops.list().getItems()
                : ops.withLabelSelector(selector).list().getItems();
        return items.stream().map(K8sInspectionService::toPodStatus).toList();
    }

    static PodStatus toPodStatus(Pod pod) {
        int restarts = containerStatuses(pod).stream()
                .mapToInt(cs -> cs.getRestartCount() == null ? 0 : cs.getRestartCount())
                .sum();
        return new PodStatus(name(pod), phase(pod), isReady(pod), restarts, age(pod));
    }

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

    public PodDescription describe(String namespace, String name) {
        Pod pod = client().pods().inNamespace(namespace).withName(name).get();
        if (pod == null) {
            throw new NotFoundException("Pod not found: " + namespace + "/" + name);
        }
        return toDescription(pod);
    }

    static PodDescription toDescription(Pod pod) {
        List<PodDescription.ContainerSpecInfo> containers = new ArrayList<>();
        if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            for (Container c : pod.getSpec().getContainers()) {
                Map<String, String> requests = resources(c, true);
                Map<String, String> limits = resources(c, false);
                containers.add(new PodDescription.ContainerSpecInfo(c.getName(), c.getImage(), requests, limits));
            }
        }
        List<PodDiagnosis.ConditionInfo> conditions = conditions(pod).stream()
                .map(c -> new PodDiagnosis.ConditionInfo(c.getType(), c.getStatus(), c.getReason()))
                .toList();
        String node = pod.getSpec() == null ? null : pod.getSpec().getNodeName();
        return new PodDescription(name(pod), namespace(pod), phase(pod), node, containers, conditions);
    }

    public List<PodDiagnosis.EventInfo> events(String namespace, String object) {
        List<Event> events = client().v1().events().inNamespace(namespace).list().getItems();
        return events.stream()
                .filter(e -> object == null || object.isBlank()
                        || (e.getInvolvedObject() != null && object.equals(e.getInvolvedObject().getName())))
                .sorted(Comparator.comparing(K8sInspectionService::eventInstant,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(K8sInspectionService::toEventInfo)
                .toList();
    }

    static PodDiagnosis.EventInfo toEventInfo(Event e) {
        return new PodDiagnosis.EventInfo(e.getType(), e.getReason(), e.getMessage(), ago(eventInstant(e)));
    }

    public String logs(String namespace, String name, String container, int lines, boolean previous) {
        return safeLogs(namespace, name, container, lines, previous);
    }

    private String safeLogs(String namespace, String name, String container, int lines, boolean previous) {
        try {
            var pod = client().pods().inNamespace(namespace).withName(name);
            var base = (container == null || container.isBlank()) ? pod : pod.inContainer(container);
            return previous
                    ? base.terminated().tailingLines(lines).getLog()
                    : base.tailingLines(lines).getLog();
        } catch (RuntimeException ex) {
            log.debug("Could not fetch logs for {}/{}: {}", namespace, name, ex.getMessage());
            return "(no logs available: " + ex.getMessage() + ")";
        }
    }

    private static String name(Pod pod) {
        return pod.getMetadata() == null ? null : pod.getMetadata().getName();
    }

    private static String namespace(Pod pod) {
        return pod.getMetadata() == null ? null : pod.getMetadata().getNamespace();
    }

    private static String phase(Pod pod) {
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

    private static String notReadyReason(Pod pod) {
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
                .orElse(phase(pod));
    }

    private static String containerState(ContainerStatus cs) {
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

    private static String stateReason(ContainerStatus cs) {
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

    private static Map<String, String> resources(Container c, boolean requests) {
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

    private static String age(Pod pod) {
        if (pod.getMetadata() == null || pod.getMetadata().getCreationTimestamp() == null) {
            return "?";
        }
        return ago(parseInstant(pod.getMetadata().getCreationTimestamp()));
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

    static String ago(Instant instant) {
        if (instant == null) {
            return "?";
        }
        long seconds = Math.max(0, Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h";
        }
        return (seconds / 86400) + "d";
    }
}
