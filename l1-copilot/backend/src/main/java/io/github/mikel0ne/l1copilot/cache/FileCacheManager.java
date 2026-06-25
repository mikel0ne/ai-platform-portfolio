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

/**
 * Caché de respuestas LLM con persistencia opcional en fichero.
 * <p>
 * El mapa en memoria es la fuente principal. Cuando
 * {@code cache.file.enabled=true} se vuelca cada entrada a {@code <cacheDir>/<key>.cache}
 * (escritura atómica vía fichero temporal + move) y al arrancar se recargan las que
 * sigan vigentes.
 */
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

    /**
     * Crea el directorio (si la persistencia está activa) y recarga las entradas vigentes.
     * Se invoca desde {@code CacheInitializer} en {@code @PostConstruct}.
     */
    public void init() {
        if (!fileEnabled) {
            log.info("Caché solo en memoria (cache.file.enabled=false)");
            return;
        }
        cacheDir = Paths.get(cachePath);
        try {
            Files.createDirectories(cacheDir);
            int loaded = loadCacheFromDisk();
            log.info("Caché inicializada en {} — {} entradas recargadas",
                    cacheDir.toAbsolutePath(), loaded);
        } catch (IOException e) {
            log.error("Error al crear directorio de caché: {}", cachePath, e);
        }
    }

    /** Obtiene un valor del caché y actualiza el contador de hits/misses. */
    public Object get(String key) {
        CacheEntry entry = memoryCache.get(key);

        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }

        if (entry.isExpired()) {
            log.debug("Entrada de caché expirada: {}", key);
            remove(key);
            misses.incrementAndGet();
            return null;
        }

        hits.incrementAndGet();
        log.debug("Valor encontrado en caché: {}", key);
        return entry.getValue();
    }

    /** Almacena un valor con el TTL por defecto. */
    public void put(String key, Object value) {
        put(key, value, defaultTtlSeconds);
    }

    /** Almacena un valor con TTL personalizado. */
    public void put(String key, Object value, long ttlSeconds) {
        CacheEntry entry = new CacheEntry(value, ttlSeconds);
        memoryCache.put(key, entry);
        if (fileEnabled) {
            saveCacheToDisk(key, entry);
        }
        log.debug("Valor almacenado en caché: {} (TTL: {} segundos)", key, ttlSeconds);
    }

    /** Elimina una entrada (memoria + disco si procede). */
    public void remove(String key) {
        memoryCache.remove(key);
        if (!fileEnabled || cacheDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(cacheDir.resolve(key + SUFFIX));
        } catch (IOException e) {
            log.error("Error al eliminar entrada de caché: {}", key, e);
        }
    }

    /** Vacía caché y contadores. */
    public void clear() {
        memoryCache.clear();
        hits.set(0);
        misses.set(0);
        if (!fileEnabled || cacheDir == null) {
            log.info("Caché en memoria limpiada");
            return;
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.error("Error al limpiar caché ({}): {}",
                                    p.getFileName(), e.getMessage());
                        }
                    });
            log.info("Caché limpiada (memoria + disco en {})", cacheDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Error al limpiar directorio de caché", e);
        }
    }

    /**
     * Escritura atómica: serializa a {@code <key>.cache.tmp} y mueve sobre el destino,
     * para que un fallo a mitad de escritura no deje un fichero corrupto que rompa la
     * próxima recarga.
     */
    private void saveCacheToDisk(String key, CacheEntry entry) {
        Path target = cacheDir.resolve(key + SUFFIX);
        Path tmp = cacheDir.resolve(key + TMP_SUFFIX);
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(tmp))) {
            oos.writeObject(entry);
        } catch (IOException e) {
            log.error("Error al escribir caché temporal para {}: {}", key, e.getMessage());
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
                log.error("Error al renombrar caché para {}: {}", key, ex.getMessage());
                deleteQuietly(tmp);
            }
        } catch (IOException e) {
            log.error("Error al mover caché para {}: {}", key, e.getMessage());
            deleteQuietly(tmp);
        }
    }

    /**
     * Recarga entradas vigentes desde disco. Ficheros corruptos, bloqueados (Windows)
     * o de versiones incompatibles se borran sin propagar el error: el arranque no
     * puede quedarse colgado por una entrada de caché podrida.
     */
    private int loadCacheFromDisk() {
        int loaded = 0;
        List<Path> files;
        try (Stream<Path> stream = Files.list(cacheDir)) {
            files = stream.filter(p -> {
                String n = p.getFileName().toString();
                // Limpia los .tmp que quedaron de un crash a mitad de escritura.
                if (n.endsWith(TMP_SUFFIX)) {
                    deleteQuietly(p);
                    return false;
                }
                return n.endsWith(SUFFIX);
            }).toList();
        } catch (IOException e) {
            log.error("Error al listar el directorio de caché {}", cacheDir, e);
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
                log.warn("Fichero de caché incompatible, se elimina: {}", fileName);
                deleteQuietly(p);
            } catch (IOException e) {
                log.warn("No se pudo leer el fichero de caché '{}': {}",
                        fileName, e.getMessage());
            }
        }
        return loaded;
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("No se pudo eliminar '{}': {}", p.getFileName(), e.getMessage());
        }
    }

    /** Estadísticas — expuestas por {@code GET /api/cache/status}. */
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
