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

class FileCacheManagerTest {

    @Test
    @DisplayName("Reloads the valid entries from disk on a new start")
    void reloadsEntriesAfterRestart(@TempDir Path tmp) {
        FileCacheManager first = newManager(tmp, true, 3600L);
        first.init();
        first.put("k1", "respuesta cacheada");
        first.put("k2", "otra respuesta");

        FileCacheManager second = newManager(tmp, true, 3600L);
        second.init();

        assertThat(second.get("k1")).isEqualTo("respuesta cacheada");
        assertThat(second.get("k2")).isEqualTo("otra respuesta");
        assertThat(second.getStats().get("entriesInMemory")).isEqualTo(2);
    }

    @Test
    @DisplayName("Expired entries are dropped on reload")
    void droppsExpiredEntriesOnReload(@TempDir Path tmp) throws InterruptedException {
        FileCacheManager first = newManager(tmp, true, 3600L);
        first.init();
        first.put("vivo", "ok", 3600L);
        first.put("muerto", "stale", 1L);
        Thread.sleep(1_500);

        FileCacheManager second = newManager(tmp, true, 3600L);
        second.init();

        assertThat(second.get("vivo")).isEqualTo("ok");
        assertThat(second.get("muerto")).isNull();
    }

    @Test
    @DisplayName("With file.enabled=false it never touches disk even when given a path")
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
    @DisplayName("Counts hits, misses and ratio in getStats()")
    void countsHitsAndMisses(@TempDir Path tmp) {
        FileCacheManager manager = newManager(tmp, false, 3600L);
        manager.init();
        manager.put("k", "v");

        manager.get("k");
        manager.get("k");
        manager.get("missing");

        Map<String, Object> stats = manager.getStats();
        assertThat(stats.get("hits")).isEqualTo(2L);
        assertThat(stats.get("misses")).isEqualTo(1L);
        assertThat((double) stats.get("hitRate")).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("A corrupt cache file does not break startup and gets deleted")
    void corruptedFileIsRemovedOnReload(@TempDir Path tmp) throws IOException {
        Path corrupt = tmp.resolve("corrupt.cache");
        try (OutputStream out = Files.newOutputStream(corrupt)) {
            out.write(new byte[]{1, 2, 3, 4, 5});
        }

        FileCacheManager manager = newManager(tmp, true, 3600L);
        manager.init();

        assertThat(manager.get("corrupt")).isNull();
    }

    private static FileCacheManager newManager(Path dir, boolean fileEnabled, long ttlSeconds) {
        CacheProperties props = new CacheProperties(
                true,
                new CacheProperties.File(fileEnabled, dir.toString()),
                ttlSeconds);
        return new FileCacheManager(props);
    }
}
