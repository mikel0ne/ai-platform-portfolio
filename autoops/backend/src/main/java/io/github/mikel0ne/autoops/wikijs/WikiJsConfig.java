package io.github.mikel0ne.autoops.wikijs;

import io.github.mikel0ne.autoops.config.HttpClientProperties;
import io.github.mikel0ne.autoops.config.RestClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WikiJsConfig {

    @Bean
    public RestClient wikiJsClient(WikiJsProperties props, HttpClientProperties http,
                                   @Value("${WIKIJS_API_KEY:}") String apiKey) {
        RestClient.Builder builder = RestClients.builder(http)
                .defaultHeader("Content-Type", "application/json");
        if (props.url() != null && !props.url().isBlank()) {
            builder.baseUrl(props.url());
        }
        if (!apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }
}
