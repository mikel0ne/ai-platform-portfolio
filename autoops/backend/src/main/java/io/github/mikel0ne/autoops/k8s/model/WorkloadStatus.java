package io.github.mikel0ne.autoops.k8s.model;

public record WorkloadStatus(String type, String name, int desired, int available) {}
