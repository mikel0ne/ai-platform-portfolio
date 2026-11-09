package io.github.mikel0ne.autoops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.info("========================================");
        log.info("✓ AutoOps MCP started");
        log.info("----------------------------------------");
        log.info("MCP (Streamable HTTP): /mcp     → tools for MCP clients (Claude Code/Copilot)");
        log.info("Chat:       POST /search        → documentation search (RAG)");
        log.info("UI:         /                   → search chat (static)");
        log.info("Health:     /actuator/health    → liveness/readiness");
        log.info("Swagger UI: /swagger-ui/index.html");
        log.info("========================================");
    }
}
