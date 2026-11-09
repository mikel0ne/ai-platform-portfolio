package io.github.mikel0ne.l1copilot.cache;

import io.github.mikel0ne.l1copilot.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
public class FileCacheManager {

    private static final String SUFFIX = ".cache";
    private static final String TMP_SUFFIX = ".cache.tmp";

    private static final Logger log = LoggerFactory.getLogger(FileCacheManager.class);

    private final boolean fileEnabled;
    private final String cachePath;
    private final long defaultTtlSeconds;

    private final Map<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private Path cacheDir;

    public FileCacheManager(CacheProperties properties) {
        this.fileEnabled = properties.file().enabled();
        this.cachePath = properties.file().path();
        this.defaultTtlSeconds = properties.ttlSeconds();
    }

    public void init() {
        if (!fileEnabled) {
            log.info("In-memory cache only (cache.file.enabled=false)");
            return;
        }
        cacheDir = Paths.get(cachePath);
        try {
            Files.createDirectories(cacheDir);
            int loaded = loadCacheFromDisk();
            log.info("Cache initialised at {}: {} entries reloaded",
                    cacheDir.toAbsolutePath(), loaded);
        } catch (IOException e) {
            log.error("Could not create the cache directory: {}", cachePath, e);
        }
    }

    public Object get(String key) {
        CacheEntry entry = memoryCache.get(key);

        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }

        if (entry.isExpired()) {
            log.debug("Cache entry expired: {}", key);
            remove(key);
            misses.incrementAndGet();
            return null;
        }

        hits.incrementAndGet();
        log.debug("Cache hit: {}", key);
        return entry.getValue();
    }

    public void put(String key, Object value) {
        put(key, value, defaultTtlSeconds);
    }

    public void put(String key, Object value, long ttlSeconds) {
        CacheEntry entry = new CacheEntry(value, ttlSeconds);
        memoryCache.put(key, entry);
        if (fileEnabled) {
            saveCacheToDisk(key, entry);
        }
        log.debug("Cached value: {} (TTL: {} seconds)", key, ttlSeconds);
    }

    public void remove(String key) {
        memoryCache.remove(key);
        if (!fileEnabled || cacheDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(cacheDir.resolve(key + SUFFIX));
        } catch (IOException e) {
            log.error("Could not delete cache entry: {}", key, e);
        }
    }

    public void clear() {
        memoryCache.clear();
        hits.set(0);
        misses.set(0);
        if (!fileEnabled || cacheDir == null) {
            log.info("In-memory cache cleared");
            return;
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.error("Could not clear the cache ({}): {}",
                                    p.getFileName(), e.getMessage());
                        }
                    });
            log.info("Cache cleared (memory and disk at {})", cacheDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Could not clear the cache directory", e);
        }
    }

    private void saveCacheToDisk(String key, CacheEntry entry) {
        Path target = cacheDir.resolve(key + SUFFIX);
        Path tmp = cacheDir.resolve(key + TMP_SUFFIX);
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(tmp))) {
            oos.writeObject(entry);
        } catch (IOException e) {
            log.error("Could not write the temporary cache file for {}: {}", key, e.getMessage());
            deleteQuietly(tmp);
            return;
        }
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                log.error("Could not rename the cache file for {}: {}", key, ex.getMessage());
                deleteQuietly(tmp);
            }
        } catch (IOException e) {
            log.error("Could not move the cache file for {}: {}", key, e.getMessage());
            deleteQuietly(tmp);
        }
    }

    private int loadCacheFromDisk() {
        int loaded = 0;
        List<Path> files;
        try (Stream<Path> stream = Files.list(cacheDir)) {
            files = stream.filter(p -> {
                String n = p.getFileName().toString();

                if (n.endsWith(TMP_SUFFIX)) {
                    deleteQuietly(p);
                    return false;
                }
                return n.endsWith(SUFFIX);
            }).toList();
        } catch (IOException e) {
            log.error("Could not list the cache directory {}", cacheDir, e);
            return 0;
        }

        for (Path p : files) {
            String fileName = p.getFileName().toString();
            String key = fileName.substring(0, fileName.length() - SUFFIX.length());
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(p))) {
                CacheEntry entry = (CacheEntry) ois.readObject();
                if (entry.isExpired()) {
                    deleteQuietly(p);
                    log.debug("Entrada expirada eliminada: {}", key);
                } else {
                    memoryCache.put(key, entry);
                    loaded++;
                    log.debug("Entrada recargada: {}", key);
                }
            } catch (ClassNotFoundException | InvalidClassException e) {
                log.warn("Incompatible cache file, deleting it: {}", fileName);
                deleteQuietly(p);
            } catch (IOException e) {
                log.warn("Could not read cache file '{}': {}",
                        fileName, e.getMessage());
            }
        }
        return loaded;
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
        if (fileEnabled && cacheDir != null) {
            stats.put("cacheDir", cacheDir.toAbsolutePath().toString());
        }
        stats.put("hits", h);
        stats.put("misses", m);
        stats.put("hitRate", total == 0 ? 0.0 : ((double) h) / total);
        return stats;
    }
}
