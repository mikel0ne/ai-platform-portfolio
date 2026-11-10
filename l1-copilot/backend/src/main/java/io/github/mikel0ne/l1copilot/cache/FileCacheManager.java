package io.github.mikel0ne.l1copilot.cache;

import io.github.mikel0ne.l1copilot.config.CacheProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

// Plain-text files (expiry on the first line, then the reply): nothing is ever deserialized from disk.
@Component
public class FileCacheManager {

    private static final String SUFFIX = ".cache";
    private static final String TMP_SUFFIX = ".cache.tmp";

    private static final Logger log = LoggerFactory.getLogger(FileCacheManager.class);

    private final boolean fileEnabled;
    private final Path cacheDir;
    private final long defaultTtlSeconds;

    private final Map<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public FileCacheManager(CacheProperties properties) {
        this.fileEnabled = properties.isCacheEnabled() && properties.file().enabled();
        this.cacheDir = Path.of(properties.file().path());
        this.defaultTtlSeconds = properties.ttlSeconds();
    }

    @PostConstruct
    public void init() {
        if (!fileEnabled) {
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            int loaded = loadCacheFromDisk();
            log.info("Cache initialised at {}: {} entries reloaded", cacheDir.toAbsolutePath(), loaded);
        } catch (IOException e) {
            log.error("Could not create the cache directory: {}", cacheDir, e);
        }
    }

    public String get(String key) {
        CacheEntry entry = memoryCache.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                remove(key);
            }
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.value();
    }

    public void put(String key, String value) {
        put(key, value, defaultTtlSeconds);
    }

    public void put(String key, String value, long ttlSeconds) {
        CacheEntry entry = new CacheEntry(value, Instant.now().plusSeconds(ttlSeconds));
        memoryCache.put(key, entry);
        if (fileEnabled) {
            saveCacheToDisk(key, entry);
        }
    }

    public void remove(String key) {
        memoryCache.remove(key);
        if (fileEnabled) {
            deleteQuietly(cacheDir.resolve(key + SUFFIX));
        }
    }

    public void clear() {
        memoryCache.clear();
        hits.set(0);
        misses.set(0);
        if (!fileEnabled) {
            return;
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).forEach(this::deleteQuietly);
            log.info("Cache cleared (memory and disk at {})", cacheDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Could not clear the cache directory", e);
        }
    }

    private void saveCacheToDisk(String key, CacheEntry entry) {
        Path target = cacheDir.resolve(key + SUFFIX);
        Path tmp = cacheDir.resolve(key + TMP_SUFFIX);
        try {
            Files.writeString(tmp, entry.expiresAt().toEpochMilli() + "\n" + entry.value(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not persist the cache entry {}: {}", key, e.getMessage());
            deleteQuietly(tmp);
        }
    }

    private int loadCacheFromDisk() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(cacheDir)) {
            files = stream.toList();
        }
        int loaded = 0;
        for (Path p : files) {
            String fileName = p.getFileName().toString();
            if (fileName.endsWith(TMP_SUFFIX)) {
                deleteQuietly(p);
            } else if (fileName.endsWith(SUFFIX)) {
                String key = fileName.substring(0, fileName.length() - SUFFIX.length());
                loaded += reload(key, p) ? 1 : 0;
            }
        }
        return loaded;
    }

    private boolean reload(String key, Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int newline = content.indexOf('\n');
            CacheEntry entry = new CacheEntry(content.substring(newline + 1),
                    Instant.ofEpochMilli(Long.parseLong(content.substring(0, newline))));
            if (entry.isExpired()) {
                deleteQuietly(file);
                return false;
            }
            memoryCache.put(key, entry);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Unreadable cache file {}, deleting it: {}", file.getFileName(), e.getMessage());
            deleteQuietly(file);
            return false;
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Could not delete '{}': {}", p.getFileName(), e.getMessage());
        }
    }

    public Map<String, Object> getStats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entriesInMemory", memoryCache.size());
        stats.put("defaultTtlSeconds", defaultTtlSeconds);
        stats.put("filePersistence", fileEnabled);
        if (fileEnabled) {
            stats.put("cacheDir", cacheDir.toAbsolutePath().toString());
        }
        stats.put("hits", h);
        stats.put("misses", m);
        stats.put("hitRate", total == 0 ? 0.0 : ((double) h) / total);
        return stats;
    }
}
