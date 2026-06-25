package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador para gestionar la caché
 */
@Tag(name = "Caché", description = "Endpoints para gestionar la caché de respuestas")
@RestController
@RequestMapping("/cache")
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
     * Limpia toda la caché. Operación destructiva de uso interno/operacional:
     * se oculta de la documentación pública ({@link Hidden}) para no anunciarla
     * como parte de la API del producto. El control de acceso real (cuando se
     * añada Spring Security) debe restringirla a un rol de administración.
     */
    @Hidden
    @DeleteMapping("/clear")
    public ResponseEntity<String> clearCache() {
        fileCacheManager.clear();
        return ResponseEntity.ok("Caché limpiada exitosamente");
    }
}

