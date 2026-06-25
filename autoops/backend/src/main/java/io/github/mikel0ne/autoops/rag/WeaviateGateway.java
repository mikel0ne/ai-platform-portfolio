package io.github.mikel0ne.autoops.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.mikel0ne.autoops.rag.crag.CragProperties;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.HybridArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acceso a Weaviate para el RAG: bootstrap del esquema, ingesta del corpus y recuperación con
 * <b>búsqueda híbrida (vector + BM25) y reranking nativo</b> en una sola llamada (doc 03 §3, doc 05 §4).
 *
 * <p>Es la implementación de producción de {@link DocRetriever}. El {@code EmbeddingModel} se inyecta de
 * forma perezosa ({@link ObjectProvider}) para no construir el cliente de Gemini al arrancar: solo se crea
 * en la primera ingesta o búsqueda.
 */
@Component
public class WeaviateGateway implements DocRetriever {

    private static final Logger log = LoggerFactory.getLogger(WeaviateGateway.class);

    private final WeaviateClient client;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final WeaviateProperties props;
    private final CragProperties crag;

    public WeaviateGateway(WeaviateClient client,
                           ObjectProvider<EmbeddingModel> embeddingModel,
                           WeaviateProperties props,
                           CragProperties crag) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.crag = crag;
    }

    /** Documento a ingestar (un chunk con su procedencia). */
    public record Doc(String texto, String fuente, String seccion, int orden) {}

    // ---------------------------------------------------------------- esquema

    /**
     * Crea la clase {@code DocChunk} si no existe (vectorizer none, reranker nativo, propiedades del doc 05
     * §4). Idempotente: no toca el esquema si ya está.
     */
    public void ensureSchema() {
        Result<Boolean> exists = client.schema().exists().withClassName(props.className()).run();
        failOnError("comprobando el esquema", exists);
        if (Boolean.TRUE.equals(exists.getResult())) {
            return;
        }
        WeaviateClass clazz = WeaviateClass.builder()
                .className(props.className())
                .description("Fragmentos de documentación interna indexados por AutoOps")
                .vectorizer("none") // los embeddings los calcula AutoOps (LangChain4j), no Weaviate
                .moduleConfig(Map.of("reranker-transformers", Map.of()))
                .properties(List.of(
                        Property.builder().name("texto").dataType(List.of("text")).build(),
                        Property.builder().name("fuente").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("seccion").dataType(List.of("text")).build(),
                        Property.builder().name("orden").dataType(List.of("int")).build()))
                .build();
        Result<Boolean> created = client.schema().classCreator().withClass(clazz).run();
        failOnError("creando la clase " + props.className(), created);
        log.info("Esquema Weaviate creado: clase {}", props.className());
    }

    /** Borra la clase entera (para reindexar desde cero). No falla si no existía. */
    public void dropSchema() {
        client.schema().classDeleter().withClassName(props.className()).run();
    }

    // ---------------------------------------------------------------- ingesta

    /** Calcula embeddings de los chunks y los inserta por lotes. Devuelve el nº de objetos insertados. */
    public int ingest(List<Doc> docs) {
        if (docs.isEmpty()) {
            return 0;
        }
        List<TextSegment> segments = docs.stream().map(d -> TextSegment.from(d.texto())).toList();
        List<Embedding> embeddings = embeddingModel.getObject().embedAll(segments).content();

        List<WeaviateObject> objects = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Doc d = docs.get(i);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("texto", d.texto());
            properties.put("fuente", d.fuente());
            properties.put("seccion", d.seccion());
            properties.put("orden", d.orden());
            objects.add(WeaviateObject.builder()
                    .className(props.className())
                    .properties(properties)
                    .vector(toFloatArray(embeddings.get(i)))
                    .build());
        }
        Result<?> batch = client.batch().objectsBatcher()
                .withObjects(objects.toArray(new WeaviateObject[0]))
                .run();
        failOnError("insertando el lote de chunks", batch);
        log.info("Ingesta Weaviate: {} chunks insertados en {}", objects.size(), props.className());
        return objects.size();
    }

    // ------------------------------------------------------------ recuperación

    @Override
    public List<DocChunk> retrieve(String consulta, int k) {
        Embedding queryVector = embeddingModel.getObject().embed(consulta).content();

        HybridArgument hybrid = HybridArgument.builder()
                .query(consulta)
                .vector(toFloatArray(queryVector))
                .alpha(crag.alpha().floatValue())
                .build();

        // El reranker de Weaviate reordena el resultado por relevancia respecto a la consulta.
        Field additional = Field.builder().name("_additional").fields(
                Field.builder().name("score").build(),
                Field.builder().name("rerank(property: \"texto\" query: \"" + escape(consulta) + "\") { score }").build()
        ).build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withHybrid(hybrid)
                .withLimit(k)
                .withFields(
                        Field.builder().name("texto").build(),
                        Field.builder().name("fuente").build(),
                        Field.builder().name("seccion").build(),
                        additional)
                .run();
        failOnError("buscando en Weaviate", result);

        return parse(result.getResult());
    }

    // -------------------------------------------------------------- parsing

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
            String texto = str(obj.get("texto"));
            String fuente = str(obj.get("fuente"));
            String seccion = str(obj.get("seccion"));
            double score = scoreOf((Map<String, Object>) obj.get("_additional"));
            chunks.add(new DocChunk(texto, fuente, seccion, score));
        }
        return chunks;
    }

    /** Score surfaced: el del reranker si está disponible; si no, el de la búsqueda híbrida. */
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

    // -------------------------------------------------------------- helpers

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

    private static void failOnError(String accion, Result<?> result) {
        if (result.hasErrors()) {
            throw new IllegalStateException("Weaviate falló " + accion + ": " + result.getError());
        }
    }
}
