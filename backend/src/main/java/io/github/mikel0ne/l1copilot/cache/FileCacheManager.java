package io.github.mikel0ne.l1copilot.cache;

import io.github.mikel0ne.l1copilot.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.io.InvalidClassException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestor de caché con persistencia en fichero
 */
@Component
public class FileCacheManager {
    private static final Logger log = LoggerFactory.getLogger(FileCacheManager.class);

    private final String cachePath;
    private final long defaultTtlSeconds;

    private final Map<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();
    private Path cacheDir;

    public FileCacheManager(CacheProperties properties) {
        this.cachePath = properties.file().path();
        this.defaultTtlSeconds = properties.ttlSeconds();
    }

    /**
     * Inicializa el gestor de caché
     */
    public void init() {
        cacheDir = Paths.get(cachePath);
        try {
            Files.createDirectories(cacheDir);
            log.info("Caché inicializada en: {}", cacheDir.toAbsolutePath());
            loadCacheFromDisk();
        } catch (IOException e) {
            log.error("Error al crear directorio de caché: {}", cachePath, e);
        }
    }

    /**
     * Obtiene un valor del caché
     */
    public Object get(String key) {
        CacheEntry entry = memoryCache.get(key);

        if (entry == null) {
            return null;
        }

        if (entry.isExpired()) {
            log.debug("Entrada de caché expirada: {}", key);
            remove(key);
            return null;
        }

        log.debug("Valor encontrado en caché: {}", key);
        return entry.getValue();
    }

    /**
     * Almacena un valor en el caché
     */
    public void put(String key, Object value) {
        put(key, value, defaultTtlSeconds);
    }

    /**
     * Almacena un valor en el caché con TTL personalizado
     */
    public void put(String key, Object value, long ttlSeconds) {
        CacheEntry entry = new CacheEntry(value, ttlSeconds);
        memoryCache.put(key, entry);
        saveCacheToDisk(key, entry);
        log.debug("Valor almacenado en caché: {} (TTL: {} segundos)", key, ttlSeconds);
    }

    /**
     * Elimina una entrada del caché
     */
    public void remove(String key) {
        memoryCache.remove(key);
        Path filePath = cacheDir.resolve(key + ".cache");
        try {
            Files.deleteIfExists(filePath);
            log.debug("Entrada de caché eliminada: {}", key);
        } catch (IOException e) {
            log.error("Error al eliminar entrada de caché: {}", key, e);
        }
    }

    /**
     * Limpia todo el caché
     */
    public void clear() {
        memoryCache.clear();
        try {
            Files.list(cacheDir)
                    .filter(p -> p.toString().endsWith(".cache"))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.error("Error al limpiar caché", e);
                        }
                    });
            log.info("Caché limpiado");
        } catch (IOException e) {
            log.error("Error al limpiar directorio de caché", e);
        }
    }

    /**
     * Guarda una entrada en disco
     */
    private void saveCacheToDisk(String key, CacheEntry entry) {
        Path filePath = cacheDir.resolve(key + ".cache");
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath.toFile()))) {
            oos.writeObject(entry);
            log.debug("Entrada de caché guardada en disco: {}", key);
        } catch (IOException e) {
            log.error("Error al guardar entrada de caché en disco: {}", key, e);
        }
    }

    /**
     * Carga las entradas de caché desde disco.
     * Los ficheros corruptos, bloqueados (Windows file lock) o con clases
     * incompatibles se eliminan silenciosamente para no bloquear el arranque.
     */
    private void loadCacheFromDisk() {
        try {
            Files.list(cacheDir)
                    .filter(p -> p.toString().endsWith(".cache"))
                    .forEach(p -> {
                        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(p.toFile()))) {
                            CacheEntry entry = (CacheEntry) ois.readObject();
                            String key = p.getFileName().toString().replace(".cache", "");

                            if (!entry.isExpired()) {
                                memoryCache.put(key, entry);
                                log.debug("Entrada de caché cargada desde disco: {}", key);
                            } else {
                                deleteQuietly(p);
                                log.debug("Entrada de caché expirada eliminada: {}", key);
                            }
                        } catch (ClassNotFoundException | InvalidClassException e) {
                            // Clase incompatible (cambio de versión): borrar y continuar
                            log.warn("Fichero de caché incompatible, se elimina: {}", p.getFileName());
                            deleteQuietly(p);
                        } catch (IOException e) {
                            // Fichero bloqueado en Windows u otro error de I/O: ignorar
                            log.warn("No se pudo leer el fichero de caché '{}': {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Error al cargar caché desde disco", e);
        }
    }

    /** Borra un fichero sin propagar excepciones (útil para ficheros bloqueados en Windows). */
    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("No se pudo eliminar el fichero de caché '{}': {}", p.getFileName(), e.getMessage());
        }
    }

    /**
     * Obtiene estadísticas del caché
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("entriesInMemory", memoryCache.size());
        stats.put("defaultTtlSeconds", defaultTtlSeconds);
        stats.put("cacheDir", cacheDir.toAbsolutePath());
        return stats;
    }
}

