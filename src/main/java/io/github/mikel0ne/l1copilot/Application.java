package io.github.mikel0ne.l1copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aplicación Spring Boot con LangChain4j + Google AI Studio (Gemini)
 */
@SpringBootApplication
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.info("========================================");
        log.info("✓ Aplicación iniciada correctamente");
        log.info("========================================");
        log.info("Endpoints disponibles:");
        log.info("  GET    /api/llm/hello    → Hola mundo");
        log.info("  POST   /api/llm/chat     → Enviar prompt");
        log.info("  GET    /api/cache/status → Estado de la caché");
        log.info("  DELETE /api/cache/clear  → Limpiar caché");
        log.info("----------------------------------------");
        log.info("Actuator endpoints:");
        log.info("  GET /actuator/health  → Estado de la aplicación");
        log.info("  GET /actuator/info    → Info de la app");
        log.info("  GET /actuator/env     → Variables y propiedades activas");
        log.info("  GET /actuator/metrics → Métricas de la JVM");
        log.info("  GET /actuator/loggers → Niveles de log");
        log.info("----------------------------------------");
        log.info("Swagger UI: http://localhost:8080/swagger-ui/index.html");
        log.info("========================================");
    }
}

