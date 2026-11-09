package io.github.mikel0ne.l1copilot.config;

import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CacheInitializer {

    @Autowired
    private FileCacheManager fileCacheManager;

    @PostConstruct
    public void init() {
        fileCacheManager.init();
    }
}
