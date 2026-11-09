package io.github.mikel0ne.autoops.k8s.model;

public record PodStatus(String name, String phase, boolean ready, int restarts, String age) {}
