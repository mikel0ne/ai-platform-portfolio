package io.github.mikel0ne.l1copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.info("L1 Support Copilot started");
        log.info("  POST   /api/copilot/chat   support agent turn (LangGraph4j)");
        log.info("  GET    /api/copilot/users  demo users");
        log.info("  GET    /api/actuator/health");
        log.info("  Swagger UI: /api/swagger-ui/index.html");
    }
}
