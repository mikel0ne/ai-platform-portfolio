package io.github.mikel0ne.autoops.github;

import io.github.mikel0ne.autoops.config.HttpClientProperties;
import io.github.mikel0ne.autoops.config.RestClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GitHubConfig {

    @Bean
    public RestClient gitHubClient(HttpClientProperties http, @Value("${GITHUB_TOKEN:}") String token) {
        RestClient.Builder builder = RestClients.builder(http)
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
    }
}
