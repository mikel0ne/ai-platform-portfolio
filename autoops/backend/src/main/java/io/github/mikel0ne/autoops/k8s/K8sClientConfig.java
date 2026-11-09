package io.github.mikel0ne.autoops.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class K8sClientConfig {

    @Bean
    @Lazy
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
