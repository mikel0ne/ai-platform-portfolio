package io.github.mikel0ne.autoops.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP de la API REST de GitHub. Construirlo no abre conexión (mismo criterio que
 * {@code WeaviateConfig}/{@code LokiConfig}). Sin {@code GITHUB_TOKEN} funciona igual pero limitado a la
 * cuota anónima (60 req/h) y solo sirve para repos públicos.
 */
@Configuration
public class GitHubConfig {

    @Bean
    public RestClient gitHubClient(@Value("${GITHUB_TOKEN:}") String token) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
    }
}
