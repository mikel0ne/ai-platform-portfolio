package io.github.mikel0ne.autoops;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApplicationContextTest {

    @Autowired
    private ToolCallbackProvider autoopsTools;

    @Test
    void wires_the_context_and_registers_every_mcp_tool() {
        assertThat(Arrays.stream(autoopsTools.getToolCallbacks()).map(tool -> tool.getToolDefinition().name()))
                .containsExactlyInAnyOrder("ping", "search_docs", "diagnose_pod", "list_pods", "list_workloads",
                        "describe_pod", "list_events", "pod_logs", "query_logs");
    }
}
