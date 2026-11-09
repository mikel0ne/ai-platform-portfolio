package io.github.mikel0ne.autoops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AutoOps MCP: servidor MCP (Spring AI) que expone, a clientes como Claude Code o GitHub Copilot,
 * tools de solo lectura para depurar errores de aplicaciones en ejecución sobre Kubernetes y para
 * consultar la documentación interna con RAG avanzado (LangChain4j + Weaviate + LangGraph4j/CRAG).
 *
 * <p>El razonamiento de cara al usuario lo pone el cliente MCP; este servidor solo da acceso a datos.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.info("========================================");
        log.info("✓ AutoOps MCP iniciado");
        log.info("----------------------------------------");
        log.info("MCP (Streamable HTTP): /mcp     → tools para clientes MCP (Claude Code/Copilot)");
        log.info("Chat:       POST /search        → búsqueda de documentación (RAG)");
        log.info("UI:         /                   → chat de búsqueda (estático)");
        log.info("Health:     /actuator/health    → liveness/readiness");
        log.info("Swagger UI: /swagger-ui/index.html");
        log.info("========================================");
    }
}
