package io.github.mikel0ne.autoops.k8s.model;

import java.util.List;
import java.util.Map;

public record PodDescription(
        String name,
        String namespace,
        String phase,
        String node,
        List<ContainerSpecInfo> containers,
        List<PodDiagnosis.ConditionInfo> conditions) {
    public record ContainerSpecInfo(String name, String image, Map<String, String> requests, Map<String, String> limits) {}
}
