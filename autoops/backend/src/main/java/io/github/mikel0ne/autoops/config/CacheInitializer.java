package io.github.mikel0ne.autoops.config;

import io.github.mikel0ne.autoops.cache.FileCacheManager;
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
