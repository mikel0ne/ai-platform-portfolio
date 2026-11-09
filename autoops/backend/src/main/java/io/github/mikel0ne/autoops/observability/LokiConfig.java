package io.github.mikel0ne.autoops.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LokiConfig {

    @Bean
    public RestClient lokiClient(LokiProperties props) {
        return RestClient.create(props.baseUrl());
    }
}
