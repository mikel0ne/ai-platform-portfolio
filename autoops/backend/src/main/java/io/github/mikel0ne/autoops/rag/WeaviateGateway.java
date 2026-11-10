package io.github.mikel0ne.autoops.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.mikel0ne.autoops.rag.crag.CragProperties;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.HybridArgument;
import io.weaviate.client.v1.graphql.query.argument.SortArgument;
import io.weaviate.client.v1.graphql.query.argument.SortOrder;
import io.weaviate.client.v1.graphql.query.argument.WhereArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WeaviateGateway implements DocRetriever {

    private static final Logger log = LoggerFactory.getLogger(WeaviateGateway.class);

    static final String TEXT = "text";
    private static final String TITLE = "title";
    private static final String SOURCE = "source";
    private static final String SECTION = "section";
    private static final String URL = "url";
    private static final String ORIGIN = "origin";
    private static final String DOC_ID = "docId";
    private static final String HASH = "hash";
    private static final String ORDER = "order";
    private static final String SECTION_CHARS = "sectionChars";

    private static final List<String> RESULT_PROPERTIES =
            List.of(TEXT, TITLE, SOURCE, SECTION, URL, ORIGIN, DOC_ID, SECTION_CHARS, ORDER);

    private final WeaviateClient client;
    // Gemini embeds documents and queries with different task types (RETRIEVAL_DOCUMENT vs RETRIEVAL_QUERY).
    private final ObjectProvider<EmbeddingModel> docEmbeddingModel;
    private final ObjectProvider<EmbeddingModel> queryEmbeddingModel;
    private final WeaviateProperties props;
    private final CragProperties crag;

    public WeaviateGateway(WeaviateClient client,
                           ObjectProvider<EmbeddingModel> docEmbeddingModel,
                           @Qualifier("queryEmbeddingModel") ObjectProvider<EmbeddingModel> queryEmbeddingModel,
                           WeaviateProperties props,
                           CragProperties crag) {
        this.client = client;
        this.docEmbeddingModel = docEmbeddingModel;
        this.queryEmbeddingModel = queryEmbeddingModel;
        this.props = props;
        this.crag = crag;
    }

    public void ensureSchema() {
        Result<Boolean> exists = client.schema().exists().withClassName(props.className()).run();
        failOnError("checking the schema", exists);
        if (Boolean.TRUE.equals(exists.getResult())) {
            return;
        }
        WeaviateClass clazz = WeaviateClass.builder()
                .className(props.className())
                .description("Internal documentation chunks indexed by AutoOps")
                .vectorizer("none")
                .moduleConfig(Map.of("reranker-transformers", Map.of()))
                .properties(List.of(
                        textProperty(TEXT, null),
                        textProperty(TITLE, null),
                        textProperty(SOURCE, "field"),
                        textProperty(SECTION, null),
                        textProperty(URL, "field"),
                        textProperty(ORIGIN, "field"),
                        textProperty(DOC_ID, "field"),
                        textProperty(HASH, "field"),
                        Property.builder().name(ORDER).dataType(List.of("int")).build(),
                        Property.builder().name(SECTION_CHARS).dataType(List.of("int")).build()))
                .build();
        Result<Boolean> created = client.schema().classCreator().withClass(clazz).run();
        failOnError("creating class " + props.className(), created);
        log.info("Weaviate schema created: class {}", props.className());
    }

    private static Property textProperty(String name, String tokenization) {
        return Property.builder().name(name).dataType(List.of("text")).tokenization(tokenization).build();
    }

    public void dropSchema() {
        client.schema().classDeleter().withClassName(props.className()).run();
    }

    private static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);

    private static final int MAX_RATE_LIMIT_RETRIES = 10;

    public int ingest(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        EmbeddingModel embedder = docEmbeddingModel.getObject();
        int ingested = 0;

        for (Chunk c : chunks) {
            Embedding embedding = embedWithRetry(embedder, c.text());
            insert(c, embedding);
            ingested++;
        }

        log.info("Weaviate ingestion: {} chunks inserted into {}", ingested, props.className());
        return ingested;
    }

    private Embedding embedWithRetry(EmbeddingModel embedder, String text) {
        for (int attempt = 1; ; attempt++) {
            try {
                return embedder.embed(TextSegment.from(text)).content();
            } catch (RuntimeException e) {
                if (!isRateLimited(e) || attempt > MAX_RATE_LIMIT_RETRIES) {
                    throw e;
                }
                log.warn("429 on embedding (Gemini rate limit), retry {}/{} in {}s",
                        attempt, MAX_RATE_LIMIT_RETRIES, RATE_LIMIT_PAUSE.getSeconds());
                sleepSeconds(RATE_LIMIT_PAUSE.getSeconds());
            }
        }
    }

    private void insert(Chunk c, Embedding embedding) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(TEXT, c.text());
        properties.put(TITLE, c.title());
        properties.put(SOURCE, c.source());
        properties.put(SECTION, c.section());
        properties.put(URL, c.url());
        properties.put(ORIGIN, c.origin());
        properties.put(DOC_ID, c.docId());
        properties.put(HASH, c.hash());
        properties.put(ORDER, c.order());
        properties.put(SECTION_CHARS, c.sectionChars());

        WeaviateObject obj = WeaviateObject.builder()
                .className(props.className())
                .properties(properties)
                .vector(toFloatArray(embedding))
                .build();

        Result<?> result = client.batch().objectsBatcher()
                .withObjects(obj)
                .run();
        failOnError("inserting chunk " + c.docId(), result);
    }

    static boolean isRateLimited(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RateLimitException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<DocChunk> retrieve(String query, int k) {
        Embedding queryVector = queryEmbeddingModel.getObject().embed(query).content();

        HybridArgument hybrid = HybridArgument.builder()
                .query(query)
                .vector(toFloatArray(queryVector))
                .alpha(crag.alpha().floatValue())
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withHybrid(hybrid)
                .withLimit(k)
                .withFields(searchFields(query))
                .run();
        failOnError("searching Weaviate", result);

        return parse(result.getResult());
    }

    @Override
    public List<DocChunk> fullSection(String docId, String section) {
        WhereFilter filter = WhereFilter.builder()
                .path(DOC_ID)
                .operator(Operator.Equal)
                .valueText(docId)
                .build();
        SortArgument byOrder = SortArgument.builder()
                .path(new String[]{ORDER})
                .order(SortOrder.asc)
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withWhere(WhereArgument.builder().filter(filter).build())
                .withSort(byOrder)
                .withFields(resultFields())
                .run();
        failOnError("fetching the section of " + docId, result);

        return parse(result.getResult()).stream()
                .filter(c -> c.section().equals(section))
                .toList();
    }

    @Override
    public List<DocChunk> neighboursInSection(String docId, String section, int order, int radius) {
        WhereFilter filter = WhereFilter.builder()
                .operator(Operator.And)
                .operands(new WhereFilter[]{
                        WhereFilter.builder().path(DOC_ID).operator(Operator.Equal).valueText(docId).build(),
                        WhereFilter.builder().path(ORDER).operator(Operator.GreaterThanEqual)
                                .valueInt(order - radius).build(),
                        WhereFilter.builder().path(ORDER).operator(Operator.LessThanEqual)
                                .valueInt(order + radius).build()
                })
                .build();
        SortArgument byOrder = SortArgument.builder()
                .path(new String[]{ORDER})
                .order(SortOrder.asc)
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withWhere(WhereArgument.builder().filter(filter).build())
                .withSort(byOrder)
                .withFields(resultFields())
                .run();
        failOnError("fetching neighbours of " + docId, result);

        return parse(result.getResult()).stream()
                .filter(c -> c.section().equals(section))
                .toList();
    }

    private static Field[] resultFields() {
        return RESULT_PROPERTIES.stream().map(name -> Field.builder().name(name).build()).toArray(Field[]::new);
    }

    static Field[] searchFields(String query) {
        Field additional = Field.builder().name("_additional").fields(
                Field.builder().name("score").build(),
                Field.builder().name(rerankExpression(query)).build()
        ).build();
        Field[] fields = Arrays.copyOf(resultFields(), RESULT_PROPERTIES.size() + 1);
        fields[RESULT_PROPERTIES.size()] = additional;
        return fields;
    }

    static String rerankExpression(String query) {
        return "rerank(property: \"" + TEXT + "\" query: \"" + escape(query) + "\") { score }";
    }

    @SuppressWarnings("unchecked")
    private List<DocChunk> parse(GraphQLResponse response) {
        List<DocChunk> chunks = new ArrayList<>();
        Object data = response == null ? null : response.getData();
        if (!(data instanceof Map<?, ?> dataMap)) {
            return chunks;
        }
        Object get = dataMap.get("Get");
        if (!(get instanceof Map<?, ?> getMap)) {
            return chunks;
        }
        Object rows = getMap.get(props.className());
        if (!(rows instanceof List<?> list)) {
            return chunks;
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> obj)) {
                continue;
            }
            String text = str(obj.get(TEXT));
            String title = str(obj.get(TITLE));
            String source = str(obj.get(SOURCE));
            String section = str(obj.get(SECTION));
            String url = str(obj.get(URL));
            String origin = str(obj.get(ORIGIN));
            String docId = str(obj.get(DOC_ID));
            int sectionChars = intOf(obj.get(SECTION_CHARS));
            int order = intOf(obj.get(ORDER));
            double score = scoreOf((Map<String, Object>) obj.get("_additional"));
            chunks.add(new DocChunk(text, title, source, section, url, origin, score, docId, sectionChars, order));
        }
        return chunks;
    }

    @SuppressWarnings("unchecked")
    private static double scoreOf(Map<String, Object> additional) {
        if (additional == null) {
            return 0.0;
        }
        Object rerank = additional.get("rerank");
        if (rerank instanceof List<?> rerankList && !rerankList.isEmpty()
                && rerankList.get(0) instanceof Map<?, ?> first) {
            Double s = toDouble(first.get("score"));
            if (s != null) {
                return s;
            }
        }
        Double hybrid = toDouble(additional.get("score"));
        return hybrid == null ? 0.0 : hybrid;
    }

    private static int intOf(Object value) {
        Double d = toDouble(value);
        return d == null ? 0 : d.intValue();
    }

    private static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ingestion interrupted while waiting to retry Gemini", e);
        }
    }

    private static Float[] toFloatArray(Embedding embedding) {
        float[] v = embedding.vector();
        Float[] boxed = new Float[v.length];
        for (int i = 0; i < v.length; i++) {
            boxed[i] = v[i];
        }
        return boxed;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\s+", " ");
    }

    private static void failOnError(String action, Result<?> result) {
        if (result.hasErrors()) {
            throw new IllegalStateException("Weaviate failed " + action + ": " + result.getError());
        }
    }
}
