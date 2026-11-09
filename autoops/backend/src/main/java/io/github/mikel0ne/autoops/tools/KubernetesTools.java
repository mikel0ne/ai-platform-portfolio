package io.github.mikel0ne.autoops.tools;

import io.github.mikel0ne.autoops.k8s.K8sInspectionService;
import io.github.mikel0ne.autoops.k8s.model.PodDescription;
import io.github.mikel0ne.autoops.k8s.model.PodDiagnosis;
import io.github.mikel0ne.autoops.k8s.model.PodStatus;
import io.github.mikel0ne.autoops.k8s.model.WorkloadStatus;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KubernetesTools {

    private static final int DEFAULT_LOG_LINES = 200;

    private final K8sInspectionService k8s;

    public KubernetesTools(K8sInspectionService k8s) {
        this.k8s = k8s;
    }

    @Tool(name = "diagnose_pod",
          description = "Diagnostic dossier for a pod: phase, conditions, not-ready reason, containers with restarts, "
                  + "recent events and log tail. Start here to debug a failing pod.")
    public PodDiagnosis diagnosePod(
            @ToolParam(description = "Pod namespace") String namespace,
            @ToolParam(description = "Pod name") String name) {
        return k8s.diagnose(namespace, name);
    }

    @Tool(name = "list_pods",
          description = "Lists the pods of a namespace with phase, READY, restarts and age.")
    public List<PodStatus> listPods(
            @ToolParam(description = "Namespace") String namespace,
            @ToolParam(required = false, description = "Label selector, e.g. app.kubernetes.io/instance=autoops-backend. "
                    + "Pods in this cluster come from a generic Helm chart, so 'app.kubernetes.io/name' is always "
                    + "'generic-app'; filter by 'instance' instead") String selector) {
        return k8s.pods(namespace, selector);
    }

    @Tool(name = "list_workloads",
          description = "Lists Deployments, StatefulSets and DaemonSets with desired vs available replicas. "
                  + "Without a namespace it scans the whole cluster.")
    public List<WorkloadStatus> listWorkloads(
            @ToolParam(required = false, description = "Namespace (optional)") String namespace) {
        return k8s.workloads(namespace);
    }

    @Tool(name = "describe_pod",
          description = "'kubectl describe'-like detail of a pod: containers, images, requests/limits and conditions.")
    public PodDescription describePod(
            @ToolParam(description = "Pod namespace") String namespace,
            @ToolParam(description = "Pod name") String name) {
        return k8s.describe(namespace, name);
    }

    @Tool(name = "list_events",
          description = "Recent events of a namespace or of a single object (type, reason, message and age).")
    public List<PodDiagnosis.EventInfo> listEvents(
            @ToolParam(description = "Namespace") String namespace,
            @ToolParam(required = false, description = "Object name to filter by (optional)") String object) {
        return k8s.events(namespace, object);
    }

    @Tool(name = "pod_logs",
          description = "Log tail of a pod (read-only). With previous=true, the logs of the previous start (useful after a crash).")
    public String podLogs(
            @ToolParam(description = "Pod namespace") String namespace,
            @ToolParam(description = "Pod name") String name,
            @ToolParam(required = false, description = "Container (when the pod has several)") String container,
            @ToolParam(required = false, description = "Number of lines (default 200)") Integer lines,
            @ToolParam(required = false, description = "Logs of the previous start") Boolean previous) {
        return k8s.logs(namespace, name, container,
                lines == null ? DEFAULT_LOG_LINES : lines,
                Boolean.TRUE.equals(previous));
    }
}
