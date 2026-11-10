package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Cache", description = "Development LLM response cache")
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true")
@RestController
@RequestMapping("/cache")
@RequiredArgsConstructor
public class CacheController {

    private final FileCacheManager fileCacheManager;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCacheStatus() {
        return ResponseEntity.ok(fileCacheManager.getStats());
    }

    @Hidden
    @DeleteMapping("/clear")
    public ResponseEntity<String> clearCache() {
        fileCacheManager.clear();
        return ResponseEntity.ok("Cache cleared");
    }
}
