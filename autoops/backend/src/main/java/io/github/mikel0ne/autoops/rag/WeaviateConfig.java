package io.github.mikel0ne.autoops.rag;

import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WeaviateConfig {

    @Bean
    public WeaviateClient weaviateClient(WeaviateProperties props) {
        String host = props.host() + ":" + props.port();
        return new WeaviateClient(new Config(props.scheme(), host));
    }
}
