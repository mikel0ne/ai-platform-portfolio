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

/**
 * Tools MCP de Kubernetes, <b>todas de solo lectura</b> (doc 03 §1.2, doc 05 §1). Devuelven datos
 * estructurados y deterministas; el razonamiento (por qué falla un pod) lo hace el cliente MCP combinando
 * tools. La tool estrella es {@code diagnosticar_pod}: un dossier con todo el contexto en una sola llamada.
 */
@Component
public class KubernetesTools {

    private final K8sInspectionService k8s;

    public KubernetesTools(K8sInspectionService k8s) {
        this.k8s = k8s;
    }

    @Tool(name = "diagnosticar_pod",
          description = "Dossier de diagnóstico de un pod: fase, condiciones, motivo de no-Ready, contenedores "
                  + "con reinicios, eventos recientes y cola de logs. Empieza por aquí para depurar un pod que falla.")
    public PodDiagnosis diagnosticarPod(
            @ToolParam(description = "Namespace del pod") String namespace,
            @ToolParam(description = "Nombre del pod") String nombre) {
        return k8s.diagnose(namespace, nombre);
    }

    @Tool(name = "estado_pods",
          description = "Lista los pods de un namespace con su fase, READY, reinicios y antigüedad.")
    public List<PodStatus> estadoPods(
            @ToolParam(description = "Namespace") String namespace,
            @ToolParam(required = false, description = "Selector de labels, p. ej. app=payments") String selector) {
        return k8s.pods(namespace, selector);
    }

    @Tool(name = "listar_workloads",
          description = "Lista Deployments/StatefulSets/DaemonSets con réplicas deseadas vs disponibles. "
                  + "Sin namespace, recorre todo el clúster.")
    public List<WorkloadStatus> listarWorkloads(
            @ToolParam(required = false, description = "Namespace (opcional)") String namespace) {
        return k8s.workloads(namespace);
    }

    @Tool(name = "describir_pod",
          description = "Detalle tipo 'kubectl describe' de un pod: contenedores, imágenes, requests/limits y condiciones.")
    public PodDescription describirPod(
            @ToolParam(description = "Namespace del pod") String namespace,
            @ToolParam(description = "Nombre del pod") String nombre) {
        return k8s.describe(namespace, nombre);
    }

    @Tool(name = "eventos",
          description = "Eventos recientes de un namespace o de un objeto concreto (tipo, motivo, mensaje y antigüedad).")
    public List<PodDiagnosis.Evento> eventos(
            @ToolParam(description = "Namespace") String namespace,
            @ToolParam(required = false, description = "Nombre del objeto para filtrar (opcional)") String objeto) {
        return k8s.events(namespace, objeto);
    }

    @Tool(name = "logs_pod",
          description = "Cola de logs de un pod (read-only). Con previous=true, los del arranque anterior (útil tras un crash).")
    public String logsPod(
            @ToolParam(description = "Namespace del pod") String namespace,
            @ToolParam(description = "Nombre del pod") String nombre,
            @ToolParam(required = false, description = "Contenedor (si el pod tiene varios)") String contenedor,
            @ToolParam(required = false, description = "Nº de líneas (por defecto 200)") Integer lineas,
            @ToolParam(required = false, description = "Logs del arranque anterior") Boolean previous) {
        return k8s.logs(namespace, nombre, contenedor,
                lineas == null ? 200 : lineas,
                Boolean.TRUE.equals(previous));
    }
}
