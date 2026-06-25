package io.github.mikel0ne.l1copilot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fija qué perfil resuelve qué propiedades de application*.yml: el general
 * (application.yml) lleva los valores por defecto (Postgres, puerto 8079,
 * ddl-auto none, turn-log file+db...) y cada perfil pisa solo lo irrepetible.
 * No arranca ningún bean real (el contexto es vacío) para no tropezar con el
 * ChatModel de Gemini, que no puede construirse sin red en este entorno.
 *
 * Nace del incidente en que server.port vivía sin querer en el application.yml
 * base y se filtró a prod sin que nadie lo notara durante una semana (ver
 * docs/infraestructura).
 */
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
    @DisplayName("dev (H2) hereda del general y solo pisa lo que de verdad cambia")
    void devOverridesOnlyWhatDiffers() {
        runWithProfile("dev", context -> {
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("dev");
            assertThat(env.getProperty("server.port")).isEqualTo("8079");                      // heredado
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");    // heredado
            assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");         // heredado
            assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.h2.Driver"); // pisado
            assertThat(env.getProperty("copilot.turn-log.file.enabled")).isEqualTo("true");    // heredado
            assertThat(env.getProperty("copilot.turn-log.db.enabled")).isEqualTo("false");     // pisado
        });
    }

    @Test
    @DisplayName("local (Postgres) hereda el driver/puerto del general y solo pisa la URL/password")
    void localOverridesOnlyWhatDiffers() {
        runWithProfile("local", context -> {
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("local");
            assertThat(env.getProperty("server.port")).isEqualTo("8079");                                  // heredado
            assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver"); // heredado
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");                // heredado
            assertThat(env.getProperty("copilot.turn-log.file.enabled")).isEqualTo("true");                // heredado
            assertThat(env.getProperty("copilot.turn-log.db.enabled")).isEqualTo("true");                  // heredado
        });
    }

    @Test
    @DisplayName("prod (K8s) pisa solo puerto y turn-log.file; el resto lo hereda del general")
    void prodOverridesOnlyWhatDiffers() {
        runWithProfile("prod", context -> {
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly("prod");
            assertThat(env.getProperty("server.port")).isEqualTo("8080");                                  // pisado
            assertThat(env.getProperty("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver"); // heredado
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");                // heredado
            assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");                     // heredado
            assertThat(env.getProperty("copilot.turn-log.file.enabled")).isEqualTo("false");               // pisado
            assertThat(env.getProperty("copilot.turn-log.db.enabled")).isEqualTo("true");                  // heredado
            assertThat(env.getProperty("management.endpoint.env.show-values")).isNull();                   // nunca heredado (seguridad)
        });
    }
}
