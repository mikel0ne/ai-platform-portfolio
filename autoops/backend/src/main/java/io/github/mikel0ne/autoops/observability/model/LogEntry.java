package io.github.mikel0ne.autoops.observability.model;

import java.time.Instant;

public record LogEntry(Instant timestamp, String namespace, String pod, String container, String line) {}
