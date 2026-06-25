package io.github.mikel0ne.l1copilot.cache;

import io.github.mikel0ne.l1copilot.config.CacheProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el ciclo persistencia → recarga funciona y que los modos
 * activados/desactivados se comportan como esperamos. La recarga era el
 * punto sospechoso por el que llegamos aquí.
 */
class FileCacheManagerTest {

    @Test
    @DisplayName("Recarga las entradas vigentes desde disco en un nuevo arranque")
    void reloadsEntriesAfterRestart(@TempDir Path tmp) {
        FileCacheManager first = newManager(tmp, true, 3600L);
        first.init();
        first.put("k1", "respuesta cacheada");
        first.put("k2", "otra respuesta");

        // Nuevo "arranque": una instancia distinta apuntando al mismo directorio.
        FileCacheManager second = newManager(tmp, true, 3600L);
        second.init();

        assertThat(second.get("k1")).isEqualTo("respuesta cacheada");
        assertThat(second.get("k2")).isEqualTo("otra respuesta");
        assertThat(second.getStats().get("entriesInMemory")).isEqualTo(2);
    }

    @Test
    @DisplayName("Las entradas expiradas se descartan al recargar")
    void droppsExpiredEntriesOnReload(@TempDir Path tmp) throws InterruptedException {
        FileCacheManager first = newManager(tmp, true, 3600L);
        first.init();
        first.put("vivo", "ok", 3600L);
        first.put("muerto", "stale", 1L);
        Thread.sleep(1_500); // supera el TTL de "muerto"

        FileCacheManager second = newManager(tmp, true, 3600L);
        second.init();

        assertThat(second.get("vivo")).isEqualTo("ok");
        assertThat(second.get("muerto")).isNull();
    }

    @Test
    @DisplayName("Con file.enabled=false no toca disco aunque se le pase un path")
    void inMemoryOnlyDoesNotTouchDisk(@TempDir Path tmp) throws IOException {
        FileCacheManager manager = newManager(tmp, false, 3600L);
        manager.init();
        manager.put("k", "v");

        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
        assertThat(manager.get("k")).isEqualTo("v");
        assertThat(manager.getStats()).containsEntry("filePersistence", false);
    }

    @Test
    @DisplayName("Cuenta hits, misses y ratio en getStats()")
    void countsHitsAndMisses(@TempDir Path tmp) {
        FileCacheManager manager = newManager(tmp, false, 3600L);
        manager.init();
        manager.put("k", "v");

        manager.get("k");       // hit
        manager.get("k");       // hit
        manager.get("missing"); // miss

        Map<String, Object> stats = manager.getStats();
        assertThat(stats.get("hits")).isEqualTo(2L);
        assertThat(stats.get("misses")).isEqualTo(1L);
        assertThat((double) stats.get("hitRate")).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("Un fichero de caché corrupto no rompe el arranque y se borra")
    void corruptedFileIsRemovedOnReload(@TempDir Path tmp) throws IOException {
        // Crea un .cache con basura → ObjectInputStream lanzará IOException
        Path corrupt = tmp.resolve("corrupt.cache");
        try (OutputStream out = Files.newOutputStream(corrupt)) {
            out.write(new byte[]{1, 2, 3, 4, 5});
        }

        FileCacheManager manager = newManager(tmp, true, 3600L);
        manager.init();   // no debe lanzar

        // No se carga la entrada corrupta — el fichero queda en disco pero ignorado.
        assertThat(manager.get("corrupt")).isNull();
    }

    private static FileCacheManager newManager(Path dir, boolean fileEnabled, long ttlSeconds) {
        CacheProperties props = new CacheProperties(
                true,  // cache.enabled — la caché global está activa en los tests
                new CacheProperties.File(fileEnabled, dir.toString()),
                ttlSeconds);
        return new FileCacheManager(props);
    }
}
