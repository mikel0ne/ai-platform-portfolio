package io.github.mikel0ne.autoops.k8s.model;

import java.util.List;

public record PodDiagnosis(
        String phase,
        boolean ready,
        String reason,
        List<ConditionInfo> conditions,
        List<ContainerInfo> containers,
        List<EventInfo> events,
        String logs) {
    public record ConditionInfo(String type, String state, String reason) {}

    public record ContainerInfo(String name, String image, int restarts, String state, String stateReason) {}

    public record EventInfo(String type, String reason, String message, String ago) {}
}
