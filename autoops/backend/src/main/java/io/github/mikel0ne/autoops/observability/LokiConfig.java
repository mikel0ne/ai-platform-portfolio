package io.github.mikel0ne.autoops.observability;

import io.github.mikel0ne.autoops.config.HttpClientProperties;
import io.github.mikel0ne.autoops.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LokiConfig {

    @Bean
    public RestClient lokiClient(LokiProperties props, HttpClientProperties http) {
        return RestClients.builder(http).baseUrl(props.baseUrl()).build();
    }
}
