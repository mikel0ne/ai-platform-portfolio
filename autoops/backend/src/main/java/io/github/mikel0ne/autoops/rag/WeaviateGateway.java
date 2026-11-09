package io.github.mikel0ne.autoops.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WeaviateGateway implements DocRetriever {

    private static final Logger log = LoggerFactory.getLogger(WeaviateGateway.class);

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
                        Property.builder().name("text").dataType(List.of("text")).build(),
                        Property.builder().name("title").dataType(List.of("text")).build(),
                        Property.builder().name("source").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("section").dataType(List.of("text")).build(),
                        Property.builder().name("url").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("origin").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("docId").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("hash").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("order").dataType(List.of("int")).build(),
                        Property.builder().name("sectionChars").dataType(List.of("int")).build()))
                .build();
        Result<Boolean> created = client.schema().classCreator().withClass(clazz).run();
        failOnError("creating class " + props.className(), created);
        log.info("Weaviate schema created: class {}", props.className());
    }

    public void dropSchema() {
        client.schema().classDeleter().withClassName(props.className()).run();
    }

    private static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);

    public int ingest(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        EmbeddingModel embedder = docEmbeddingModel.getObject();
        int ingested = 0;

        for (Chunk c : chunks) {
            Embedding embedding = embedWithRetry(embedder, c.text());
            insertWithRetry(c, embedding);
            ingested++;
        }

        log.info("Weaviate ingestion: {} chunks inserted into {}", ingested, props.className());
        return ingested;
    }

    private Embedding embedWithRetry(EmbeddingModel embedder, String text) {
        while (true) {
            try {
                return embedder.embed(TextSegment.from(text)).content();
            } catch (Exception e) {
                if (isRateLimited(e)) {
                    log.warn("429 on embedding (Gemini rate limit), retrying in 60s");
                    sleepSeconds(RATE_LIMIT_PAUSE.getSeconds());
                } else {
                    throw e;
                }
            }
        }
    }

    private void insertWithRetry(Chunk c, Embedding embedding) {
        while (true) {
            try {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("text", c.text());
                properties.put("title", c.title());
                properties.put("source", c.source());
                properties.put("section", c.section());
                properties.put("url", c.url());
                properties.put("origin", c.origin());
                properties.put("docId", c.docId());
                properties.put("hash", c.hash());
                properties.put("order", c.order());
                properties.put("sectionChars", c.sectionChars());

                WeaviateObject obj = WeaviateObject.builder()
                        .className(props.className())
                        .properties(properties)
                        .vector(toFloatArray(embedding))
                        .build();

                Result<?> result = client.batch().objectsBatcher()
                        .withObjects(obj)
                        .run();
                failOnError("insertando chunk " + c.docId(), result);
                return;
            } catch (Exception e) {
                if (isRateLimited(e)) {
                    log.warn("429 on insert (Gemini rate limit), retrying in 60s");
                    sleepSeconds(RATE_LIMIT_PAUSE.getSeconds());
                } else {
                    throw e;
                }
            }
        }
    }

    private static boolean isRateLimited(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("rate limit"));
    }

    @Override
    public List<DocChunk> retrieve(String query, int k) {
        Embedding queryVector = queryEmbeddingModel.getObject().embed(query).content();

        HybridArgument hybrid = HybridArgument.builder()
                .query(query)
                .vector(toFloatArray(queryVector))
                .alpha(crag.alpha().floatValue())
                .build();

        Field additional = Field.builder().name("_additional").fields(
                Field.builder().name("score").build(),
                Field.builder().name("rerank(property: \"texto\" query: \"" + escape(query) + "\") { score }").build()
        ).build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withHybrid(hybrid)
                .withLimit(k)
                .withFields(
                        Field.builder().name("text").build(),
                        Field.builder().name("title").build(),
                        Field.builder().name("source").build(),
                        Field.builder().name("section").build(),
                        Field.builder().name("url").build(),
                        Field.builder().name("origin").build(),
                        Field.builder().name("docId").build(),
                        Field.builder().name("sectionChars").build(),
                        Field.builder().name("order").build(),
                        additional)
                .run();
        failOnError("searching Weaviate", result);

        return parse(result.getResult());
    }

    @Override
    public List<DocChunk> fullSection(String docId, String section) {
        WhereFilter filter = WhereFilter.builder()
                .path("docId")
                .operator(Operator.Equal)
                .valueText(docId)
                .build();
        SortArgument byOrder = SortArgument.builder()
                .path(new String[]{"order"})
                .order(SortOrder.asc)
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withWhere(WhereArgument.builder().filter(filter).build())
                .withSort(byOrder)
                .withFields(
                        Field.builder().name("text").build(),
                        Field.builder().name("title").build(),
                        Field.builder().name("source").build(),
                        Field.builder().name("section").build(),
                        Field.builder().name("url").build(),
                        Field.builder().name("origin").build(),
                        Field.builder().name("docId").build(),
                        Field.builder().name("sectionChars").build(),
                        Field.builder().name("order").build())
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
                        WhereFilter.builder().path("docId").operator(Operator.Equal).valueText(docId).build(),
                        WhereFilter.builder().path("order").operator(Operator.GreaterThanEqual)
                                .valueInt(order - radius).build(),
                        WhereFilter.builder().path("order").operator(Operator.LessThanEqual)
                                .valueInt(order + radius).build()
                })
                .build();
        SortArgument byOrder = SortArgument.builder()
                .path(new String[]{"order"})
                .order(SortOrder.asc)
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withWhere(WhereArgument.builder().filter(filter).build())
                .withSort(byOrder)
                .withFields(
                        Field.builder().name("text").build(),
                        Field.builder().name("title").build(),
                        Field.builder().name("source").build(),
                        Field.builder().name("section").build(),
                        Field.builder().name("url").build(),
                        Field.builder().name("origin").build(),
                        Field.builder().name("docId").build(),
                        Field.builder().name("sectionChars").build(),
                        Field.builder().name("order").build())
                .run();
        failOnError("fetching neighbours of " + docId, result);

        return parse(result.getResult()).stream()
                .filter(c -> c.section().equals(section))
                .toList();
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
            String text = str(obj.get("text"));
            String title = str(obj.get("title"));
            String source = str(obj.get("source"));
            String section = str(obj.get("section"));
            String url = str(obj.get("url"));
            String origin = str(obj.get("origin"));
            String docId = str(obj.get("docId"));
            int sectionChars = intOf(obj.get("sectionChars"));
            int order = intOf(obj.get("order"));
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void failOnError(String action, Result<?> result) {
        if (result.hasErrors()) {
            throw new IllegalStateException("Weaviate failed " + action + ": " + result.getError());
        }
    }
}
