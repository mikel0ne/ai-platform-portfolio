package io.github.mikel0ne.autoops.wikijs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WikiJsConfig {

    @Bean
    public RestClient wikiJsClient(WikiJsProperties props, @Value("${WIKIJS_API_KEY:}") String apiKey) {
        RestClient.Builder builder = RestClient.builder()
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
