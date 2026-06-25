package io.github.mikel0ne.autoops.k8s;

import io.github.mikel0ne.autoops.k8s.model.PodDescription;
import io.github.mikel0ne.autoops.k8s.model.PodDiagnosis;
import io.github.mikel0ne.autoops.k8s.model.PodStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapeo modelo K8s → DTO con objetos de fixture (sin clúster real, doc 03 §5). Escenario clásico de pod
 * roto: imagen inexistente → {@code ErrImagePull}.
 */
class K8sInspectionMappingTest {

    private Pod errImagePullPod() {
        return new PodBuilder()
                .withNewMetadata()
                    .withName("payments-api-7d9f-abc")
                    .withNamespace("default")
                    .withCreationTimestamp("2024-01-01T00:00:00Z")
                .endMetadata()
                .withNewStatus()
                    .withPhase("Pending")
                    .addNewCondition()
                        .withType("Ready").withStatus("False").withReason("ContainersNotReady")
                    .endCondition()
                    .addNewContainerStatus()
                        .withName("app")
                        .withImage("registry/payments:9.9.9")
                        .withRestartCount(0)
                        .withNewState().withNewWaiting().withReason("ErrImagePull").endWaiting().endState()
                    .endContainerStatus()
                .endStatus()
                .build();
    }

    @Test
    void diagnose_extrae_motivo_y_contenedor_de_un_errimagepull() {
        PodDiagnosis d = K8sInspectionService.toDiagnosis(errImagePullPod(), List.of(), "(sin logs)");

        assertThat(d.fase()).isEqualTo("Pending");
        assertThat(d.ready()).isFalse();
        assertThat(d.motivo()).isEqualTo("ErrImagePull");
        assertThat(d.condiciones()).extracting(PodDiagnosis.Condicion::tipo).contains("Ready");
        assertThat(d.contenedores()).singleElement().satisfies(c -> {
            assertThat(c.nombre()).isEqualTo("app");
            assertThat(c.imagen()).isEqualTo("registry/payments:9.9.9");
            assertThat(c.estado()).isEqualTo("Waiting");
            assertThat(c.motivoEstado()).isEqualTo("ErrImagePull");
        });
    }

    @Test
    void podstatus_resume_fase_ready_y_reinicios() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("ok").withCreationTimestamp("2024-01-01T00:00:00Z").endMetadata()
                .withNewStatus()
                    .withPhase("Running")
                    .addNewCondition().withType("Ready").withStatus("True").endCondition()
                    .addNewContainerStatus().withName("app").withRestartCount(3)
                        .withNewState().withNewRunning().endRunning().endState()
                    .endContainerStatus()
                .endStatus()
                .build();

        PodStatus s = K8sInspectionService.toPodStatus(pod);

        assertThat(s.fase()).isEqualTo("Running");
        assertThat(s.ready()).isTrue();
        assertThat(s.reinicios()).isEqualTo(3);
    }

    @Test
    void describe_expone_imagen_del_contenedor() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("p").withNamespace("default").endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("app").withImage("img:1.0").endContainer()
                .endSpec()
                .withNewStatus().withPhase("Running").endStatus()
                .build();

        PodDescription d = K8sInspectionService.toDescription(pod);

        assertThat(d.contenedores()).singleElement().satisfies(c -> {
            assertThat(c.nombre()).isEqualTo("app");
            assertThat(c.imagen()).isEqualTo("img:1.0");
        });
    }

    @Test
    void hace_formatea_antiguedad_legible() {
        assertThat(K8sInspectionService.hace(Instant.now().minusSeconds(30))).isEqualTo("30s");
        assertThat(K8sInspectionService.hace(Instant.now().minusSeconds(120))).isEqualTo("2m");
        assertThat(K8sInspectionService.hace(Instant.now().minusSeconds(7200))).isEqualTo("2h");
        assertThat(K8sInspectionService.hace(null)).isEqualTo("?");
    }
}
