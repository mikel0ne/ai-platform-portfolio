package io.github.mikel0ne.autoops.config;

import io.github.mikel0ne.platform.commons.cache.FileCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class CacheConfig {

    @Bean(initMethod = "init")
    public FileCacheManager fileCacheManager(CacheProperties properties) {
        return new FileCacheManager(properties.isCacheEnabled(), properties.file().enabled(),
                Path.of(properties.file().path()), Duration.ofSeconds(properties.ttlSeconds()));
    }
}
