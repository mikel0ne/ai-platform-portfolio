package io.github.mikel0ne.autoops.observability.langfuse;

import io.opentelemetry.api.common.AttributeKey;

import java.util.List;

// Attributes outside Langfuse's OTLP mapping land in a non-filterable metadata bag, so everything worth
// filtering on goes under the langfuse.*.metadata.* prefixes. The semconv gen_ai.* constants are alpha.
public final class LangfuseAttributes {

    public static final AttributeKey<String> TRACE_NAME = AttributeKey.stringKey("langfuse.trace.name");
    public static final AttributeKey<List<String>> TRACE_TAGS = AttributeKey.stringArrayKey("langfuse.trace.tags");
    public static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey("langfuse.environment");
    public static final AttributeKey<String> RELEASE = AttributeKey.stringKey("langfuse.release");

    public static final String TRACE_METADATA_PREFIX = "langfuse.trace.metadata.";

    public static final AttributeKey<String> OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    public static final AttributeKey<String> OBSERVATION_LEVEL = AttributeKey.stringKey("langfuse.observation.level");
    public static final AttributeKey<String> OBSERVATION_STATUS_MESSAGE =
            AttributeKey.stringKey("langfuse.observation.status_message");
    public static final AttributeKey<String> OBSERVATION_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    public static final AttributeKey<String> OBSERVATION_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    public static final AttributeKey<String> MODEL_NAME = AttributeKey.stringKey("langfuse.observation.model.name");
    public static final AttributeKey<String> MODEL_PARAMETERS =
            AttributeKey.stringKey("langfuse.observation.model.parameters");
    public static final AttributeKey<String> USAGE_DETAILS =
            AttributeKey.stringKey("langfuse.observation.usage_details");

    public static final String OBSERVATION_METADATA_PREFIX = "langfuse.observation.metadata.";

    public static final String TYPE_SPAN = "span";
    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_CHAIN = "chain";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_RETRIEVER = "retriever";

    public static final String LEVEL_ERROR = "ERROR";

    private LangfuseAttributes() {
    }
}
