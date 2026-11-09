package io.github.mikel0ne.autoops.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider autoopsTools(DiagnosticsTools diagnostics,
                                             DocsTools docs,
                                             KubernetesTools kubernetes,
                                             ObservabilityTools observability) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(diagnostics, docs, kubernetes, observability)
                .build();
    }
}
