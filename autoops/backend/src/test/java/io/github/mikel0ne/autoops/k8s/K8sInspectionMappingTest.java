package io.github.mikel0ne.autoops.k8s;

import io.github.mikel0ne.autoops.k8s.model.PodDescription;
import io.github.mikel0ne.autoops.k8s.model.PodDiagnosis;
import io.github.mikel0ne.autoops.k8s.model.PodStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

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
    void diagnose_extracts_reason_and_container_from_err_image_pull() {
        PodDiagnosis d = K8sInspectionService.toDiagnosis(errImagePullPod(), List.of(), "(sin logs)");

        assertThat(d.phase()).isEqualTo("Pending");
        assertThat(d.ready()).isFalse();
        assertThat(d.reason()).isEqualTo("ErrImagePull");
        assertThat(d.conditions()).extracting(PodDiagnosis.ConditionInfo::type).contains("Ready");
        assertThat(d.containers()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("app");
            assertThat(c.image()).isEqualTo("registry/payments:9.9.9");
            assertThat(c.state()).isEqualTo("Waiting");
            assertThat(c.stateReason()).isEqualTo("ErrImagePull");
        });
    }

    @Test
    void podStatus_summarises_phase_ready_and_restarts() {
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

        assertThat(s.phase()).isEqualTo("Running");
        assertThat(s.ready()).isTrue();
        assertThat(s.restarts()).isEqualTo(3);
    }

    @Test
    void describe_exposes_container_image() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("p").withNamespace("default").endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("app").withImage("img:1.0").endContainer()
                .endSpec()
                .withNewStatus().withPhase("Running").endStatus()
                .build();

        PodDescription d = K8sInspectionService.toDescription(pod);

        assertThat(d.containers()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("app");
            assertThat(c.image()).isEqualTo("img:1.0");
        });
    }

    @Test
    void keeps_only_the_most_recent_events() {
        List<Event> events = IntStream.range(0, K8sInspectionService.MAX_EVENTS + 10)
                .mapToObj(i -> new EventBuilder()
                        .withReason("r" + i)
                        .withLastTimestamp(Instant.parse("2026-06-01T10:00:00Z").plusSeconds(i).toString())
                        .build())
                .toList();

        List<PodDiagnosis.EventInfo> recent = K8sInspectionService.mostRecent(events);

        assertThat(recent).hasSize(K8sInspectionService.MAX_EVENTS);
        assertThat(recent.get(recent.size() - 1).reason()).isEqualTo("r" + (K8sInspectionService.MAX_EVENTS + 9));
    }

    @Test
    void ago_formats_a_readable_age() {
        assertThat(K8sInspectionService.ago(Instant.now().minusSeconds(30))).isEqualTo("30s");
        assertThat(K8sInspectionService.ago(Instant.now().minusSeconds(120))).isEqualTo("2m");
        assertThat(K8sInspectionService.ago(Instant.now().minusSeconds(7200))).isEqualTo("2h");
        assertThat(K8sInspectionService.ago(null)).isEqualTo("?");
    }
}
