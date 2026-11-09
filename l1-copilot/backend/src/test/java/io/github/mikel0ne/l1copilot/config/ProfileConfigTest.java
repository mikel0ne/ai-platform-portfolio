package io.github.mikel0ne.l1copilot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileConfigTest {

    @Configuration
    static class EmptyConfig {
    }

    private void runWithProfile(String profile, ContextConsumer<ConfigurableApplicationContext> consumer) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile)
                .withUserConfiguration(EmptyConfig.class)
                .run(consumer);
    }

    @Test
    @DisplayName("dev (H2) inherits from the base config and only overrides what really changes")
    void devOverridesOnlyWhatDiffers() {
        runWithProfile("dev", context -> {
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("dev");
            assertThat(env.getProperty("server.port")).isEqualTo("8079");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
            assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
            assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.h2.Driver");
            assertThat(env.getProperty("copilot.turn-log.file.enabled")).isEqualTo("true");
            assertThat(env.getProperty("copilot.turn-log.db.enabled")).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("local (Postgres) inherits driver/port from the base config and only overrides URL/password")
    void localOverridesOnlyWhatDiffers() {
        runWithProfile("local", context -> {
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("local");
            assertThat(env.getProperty("server.port")).isEqualTo("8079");
            assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
            assertThat(env.getProperty("copilot.turn-log.file.enabled")).isEqualTo("true");
            assertThat(env.getProperty("copilot.turn-log.db.enabled")).isEqualTo("true");
        });
    }

    @Test
    @DisplayName("prod (K8s) only overrides port and turn-log.file; everything else is inherited")
    void prodOverridesOnlyWhatDiffers() {
        runWithProfile("prod", context -> {
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("prod");
            assertThat(env.getProperty("server.port")).isEqualTo("8080");
            assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
            assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
            assertThat(env.getProperty("copilot.turn-log.file.enabled")).isEqualTo("false");
            assertThat(env.getProperty("copilot.turn-log.db.enabled")).isEqualTo("true");
            assertThat(env.getProperty("management.endpoint.env.show-values")).isNull();
        });
    }
}
