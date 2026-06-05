package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador para gestionar la caché
 */
@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheController {

    private final FileCacheManager fileCacheManager;

    /**
     * Obtiene el estado de la caché
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCacheStatus() {
        return ResponseEntity.ok(fileCacheManager.getStats());
    }

    /**
     * Limpia toda la caché
     */
    @DeleteMapping("/clear")
    public ResponseEntity<String> clearCache() {
        fileCacheManager.clear();
        return ResponseEntity.ok("Caché limpiada exitosamente");
    }
}

