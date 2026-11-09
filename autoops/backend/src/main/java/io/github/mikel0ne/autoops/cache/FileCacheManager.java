package io.github.mikel0ne.autoops.cache;

import io.github.mikel0ne.autoops.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component
public class FileCacheManager {

    private static final Logger log = LoggerFactory.getLogger(FileCacheManager.class);

    private final CacheProperties props;
    private final ConcurrentHashMap<String, Entry> memory = new ConcurrentHashMap<>();

    public FileCacheManager(CacheProperties props) {
        this.props = props;
    }

    private record Entry(String value, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public void init() {
        if (!props.isCacheEnabled() || !Boolean.TRUE.equals(props.file().enabled())) {
            return;
        }
        Path dir = Path.of(props.file().path());
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            long ttl = props.ttlSeconds();
            files.filter(p -> p.toString().endsWith(".txt")).forEach(p -> {
                try {
                    String key = p.getFileName().toString().replaceFirst("\\.txt$", "");
                    String value = Files.readString(p, StandardCharsets.UTF_8);
                    Instant expires = Files.getLastModifiedTime(p).toInstant().plusSeconds(ttl);
                    if (Instant.now().isBefore(expires)) {
                        memory.put(key, new Entry(value, expires));
                    }
                } catch (IOException ignored) {
                }
            });
            log.info("LLM cache loaded: {} entries from {}", memory.size(), dir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not load the LLM cache from {}: {}", dir, e.getMessage());
        }
    }

    public Optional<String> get(String key) {
        if (!props.isCacheEnabled()) {
            return Optional.empty();
        }
        Entry entry = memory.get(key);
        if (entry == null || entry.expired()) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void put(String key, String value) {
        if (!props.isCacheEnabled() || value == null) {
            return;
        }
        memory.put(key, new Entry(value, Instant.now().plusSeconds(props.ttlSeconds())));
        if (Boolean.TRUE.equals(props.file().enabled())) {
            persist(key, value);
        }
    }

    public static String key(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    private void persist(String key, String value) {
        try {
            Path dir = Path.of(props.file().path());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".txt"), value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist the LLM cache", e);
        }
    }
}
