package io.github.mikel0ne.platform.commons.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileCacheManagerTest {

    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    @DisplayName("Reloads the valid entries from disk on a new start")
    void reloadsEntriesAfterRestart(@TempDir Path tmp) {
        FileCacheManager first = new FileCacheManager(true, true, tmp, HOUR);
        first.init();
        first.put("k1", "respuesta cacheada");
        first.put("k2", "otra respuesta");

        FileCacheManager second = new FileCacheManager(true, true, tmp, HOUR);
        second.init();

        assertThat(second.get("k1")).contains("respuesta cacheada");
        assertThat(second.get("k2")).contains("otra respuesta");
        assertThat(second.getStats().get("entriesInMemory")).isEqualTo(2);
    }

    @Test
    @DisplayName("Expired entries are dropped on reload")
    void dropsExpiredEntriesOnReload(@TempDir Path tmp) throws InterruptedException {
        FileCacheManager first = new FileCacheManager(true, true, tmp, HOUR);
        first.init();
        first.put("vivo", "ok", HOUR);
        first.put("muerto", "stale", Duration.ofSeconds(1));
        Thread.sleep(1_500);

        FileCacheManager second = new FileCacheManager(true, true, tmp, HOUR);
        second.init();

        assertThat(second.get("vivo")).contains("ok");
        assertThat(second.get("muerto")).isEmpty();
    }

    @Test
    @DisplayName("An entry past its ttl is a miss and its file is removed")
    void expiredEntryIsAMiss(@TempDir Path tmp) {
        FileCacheManager manager = new FileCacheManager(true, true, tmp, HOUR);
        manager.init();
        manager.put("k", "v", Duration.ZERO);

        assertThat(manager.get("k")).isEmpty();
        assertThat(tmp.resolve("k.cache")).doesNotExist();
    }

    @Test
    @DisplayName("With file persistence off it never touches disk even when given a path")
    void inMemoryOnlyDoesNotTouchDisk(@TempDir Path tmp) throws IOException {
        FileCacheManager manager = new FileCacheManager(true, false, tmp, HOUR);
        manager.init();
        manager.put("k", "v");

        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
        assertThat(manager.get("k")).contains("v");
        assertThat(manager.getStats()).containsEntry("filePersistence", false);
    }

    @Test
    @DisplayName("A disabled cache stores nothing and always answers empty")
    void disabledCacheIsInert(@TempDir Path tmp) throws IOException {
        FileCacheManager manager = new FileCacheManager(false, true, tmp, HOUR);
        manager.init();
        manager.put("k", "v");

        assertThat(manager.get("k")).isEmpty();
        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("Counts hits, misses and ratio in getStats()")
    void countsHitsAndMisses(@TempDir Path tmp) {
        FileCacheManager manager = new FileCacheManager(true, false, tmp, HOUR);
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

        FileCacheManager manager = new FileCacheManager(true, true, tmp, HOUR);
        manager.init();

        assertThat(manager.get("corrupt")).isEmpty();
        assertThat(corrupt).doesNotExist();
    }

    @Test
    @DisplayName("Leftover temp files from an interrupted write are deleted on start")
    void leftoverTempFilesAreDeleted(@TempDir Path tmp) throws IOException {
        Path leftover = Files.writeString(tmp.resolve("half.cache.tmp"), "123\nhalf");

        new FileCacheManager(true, true, tmp, HOUR).init();

        assertThat(leftover).doesNotExist();
    }

    @Test
    @DisplayName("clear() empties memory, disk and counters")
    void clearEmptiesEverything(@TempDir Path tmp) throws IOException {
        FileCacheManager manager = new FileCacheManager(true, true, tmp, HOUR);
        manager.init();
        manager.put("k", "v");
        manager.get("k");

        manager.clear();

        assertThat(manager.getStats()).containsEntry("hits", 0L).containsEntry("entriesInMemory", 0);
        assertThat(manager.get("k")).isEmpty();
        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("remove() drops one entry from memory and disk")
    void removeDropsOneEntry(@TempDir Path tmp) {
        FileCacheManager manager = new FileCacheManager(true, true, tmp, HOUR);
        manager.init();
        manager.put("a", "1");
        manager.put("b", "2");

        manager.remove("a");

        assertThat(manager.get("a")).isEmpty();
        assertThat(manager.get("b")).contains("2");
        assertThat(tmp.resolve("a.cache")).doesNotExist();
        assertThat(tmp.resolve("b.cache")).exists();
    }

    @Test
    @DisplayName("The key is a stable url-safe SHA-256 and differs per input")
    void keyIsStableAndUrlSafe() {
        assertThat(FileCacheManager.key("prompt")).isEqualTo(FileCacheManager.key("prompt"))
                .isNotEqualTo(FileCacheManager.key("other prompt"))
                .matches("[A-Za-z0-9_-]{43}");
    }
}
