package io.github.mikel0ne.l1copilot.observability;

import io.opentelemetry.api.common.AttributeKey;

import java.util.List;

public final class LangfuseAttributes {

    public static final String TRACE_NAME = "langfuse.trace.name";
    public static final String SESSION_ID = "langfuse.session.id";
    public static final String USER_ID = "langfuse.user.id";
    public static final String ENVIRONMENT = "langfuse.environment";
    public static final String RELEASE = "langfuse.release";
    public static final AttributeKey<List<String>> TRACE_TAGS =
            AttributeKey.stringArrayKey("langfuse.trace.tags");
    public static final String TRACE_METADATA_PREFIX = "langfuse.trace.metadata.";

    public static final String OBSERVATION_TYPE = "langfuse.observation.type";
    public static final String OBSERVATION_LEVEL = "langfuse.observation.level";
    public static final String OBSERVATION_INPUT = "langfuse.observation.input";
    public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";
    public static final String MODEL_NAME = "langfuse.observation.model.name";
    public static final String MODEL_PARAMETERS = "langfuse.observation.model.parameters";
    public static final String USAGE_DETAILS = "langfuse.observation.usage_details";
    public static final String COST_DETAILS = "langfuse.observation.cost_details";
    public static final String OBSERVATION_METADATA_PREFIX = "langfuse.observation.metadata.";

    public static final String TYPE_AGENT = "agent";
    public static final String TYPE_SPAN = "span";
    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_RETRIEVER = "retriever";

    public static final String LEVEL_ERROR = "ERROR";

    private LangfuseAttributes() {
    }
}
