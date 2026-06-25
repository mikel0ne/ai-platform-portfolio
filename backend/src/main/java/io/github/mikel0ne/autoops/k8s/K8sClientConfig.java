package io.github.mikel0ne.autoops.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Cliente Kubernetes (fabric8) <b>de solo lectura</b> (el RBAC del ServiceAccount lo garantiza a nivel de
 * clúster — guía de infra 02). El builder autodetecta la configuración: kubeconfig en local, ServiceAccount
 * in-cluster en K8s.
 *
 * <p>{@code @Lazy}: el cliente se crea en el primer uso de una tool de Kubernetes, no al arrancar, para que
 * el servidor MCP levante aunque no haya clúster accesible (mismo criterio que los beans LLM del doc 06).
 */
@Configuration
public class K8sClientConfig {

    @Bean
    @Lazy
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
