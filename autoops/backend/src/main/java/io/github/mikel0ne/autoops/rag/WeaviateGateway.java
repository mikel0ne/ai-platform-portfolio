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
    private final ObjectProvider<EmbeddingModel> docEmbeddingModel;    // taskType RETRIEVAL_DOCUMENT (indexar)
    private final ObjectProvider<EmbeddingModel> queryEmbeddingModel;  // taskType RETRIEVAL_QUERY (buscar)
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
                        Property.builder().name("titulo").dataType(List.of("text")).build(),
                        Property.builder().name("fuente").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("seccion").dataType(List.of("text")).build(),
                        Property.builder().name("url").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("origen").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("docId").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("hash").dataType(List.of("text")).tokenization("field").build(),
                        Property.builder().name("orden").dataType(List.of("int")).build(),
                        Property.builder().name("seccionChars").dataType(List.of("int")).build()))
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

    /** Pausa antes de reintentar en 429 (Gemini rate limit). */
    private static final Duration PAUSA_429 = Duration.ofSeconds(60);

    /**
     * Ingesta chunk por chunk. Cada chunk se embeddia e inserta individualmente.
     * Si hay un 429 (rate limit de Gemini), espera 1 minuto y reintenta indefinidamente.
     * Otros errores: fallan el proceso.
     */
    public int ingest(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        EmbeddingModel embedder = docEmbeddingModel.getObject();
        int ingestados = 0;

        for (Chunk c : chunks) {
            Embedding embedding = embedConReintentos429(embedder, c.texto());
            insertConReintentos429(c, embedding);
            ingestados++;
        }

        log.info("Ingesta Weaviate: {} chunks insertados en {}", ingestados, props.className());
        return ingestados;
    }

    private Embedding embedConReintentos429(EmbeddingModel embedder, String texto) {
        while (true) {
            try {
                return embedder.embed(TextSegment.from(texto)).content();
            } catch (Exception e) {
                if (es429(e)) {
                    log.warn("429 en embedding (Gemini rate limit). Esperando 60s antes de reintentar...");
                    esperarSegundos(PAUSA_429.getSeconds());
                } else {
                    throw e;
                }
            }
        }
    }

    private void insertConReintentos429(Chunk c, Embedding embedding) {
        while (true) {
            try {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("texto", c.texto());
                properties.put("titulo", c.titulo());
                properties.put("fuente", c.fuente());
                properties.put("seccion", c.seccion());
                properties.put("url", c.url());
                properties.put("origen", c.origen());
                properties.put("docId", c.docId());
                properties.put("hash", c.hash());
                properties.put("orden", c.orden());
                properties.put("seccionChars", c.seccionChars());

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
                if (es429(e)) {
                    log.warn("429 en insert (Gemini rate limit). Esperando 60s antes de reintentar...");
                    esperarSegundos(PAUSA_429.getSeconds());
                } else {
                    throw e;
                }
            }
        }
    }

    private static boolean es429(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("rate limit"));
    }

    // ------------------------------------------------------------ recuperación

    @Override
    public List<DocChunk> retrieve(String consulta, int k) {
        Embedding queryVector = queryEmbeddingModel.getObject().embed(consulta).content();

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
                        Field.builder().name("titulo").build(),
                        Field.builder().name("fuente").build(),
                        Field.builder().name("seccion").build(),
                        Field.builder().name("url").build(),
                        Field.builder().name("origen").build(),
                        Field.builder().name("docId").build(),
                        Field.builder().name("seccionChars").build(),
                        Field.builder().name("orden").build(),
                        additional)
                .run();
        failOnError("buscando en Weaviate", result);

        return parse(result.getResult());
    }

    @Override
    public List<DocChunk> seccionCompleta(String docId, String seccion) {
        WhereFilter filtro = WhereFilter.builder()
                .path("docId")
                .operator(Operator.Equal)
                .valueText(docId)
                .build();
        SortArgument porOrden = SortArgument.builder()
                .path(new String[]{"orden"})
                .order(SortOrder.asc)
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withWhere(WhereArgument.builder().filter(filtro).build())
                .withSort(porOrden)
                .withFields(
                        Field.builder().name("texto").build(),
                        Field.builder().name("titulo").build(),
                        Field.builder().name("fuente").build(),
                        Field.builder().name("seccion").build(),
                        Field.builder().name("url").build(),
                        Field.builder().name("origen").build(),
                        Field.builder().name("docId").build(),
                        Field.builder().name("seccionChars").build(),
                        Field.builder().name("orden").build())
                .run();
        failOnError("recuperando la sección de " + docId, result);

        // 'seccion' es texto con tokenización por palabra (para BM25), así que el match exacto del
        // breadcrumb se hace aquí, sobre los núcleos ya filtrados por docId (índice exacto, barato).
        return parse(result.getResult()).stream()
                .filter(c -> c.seccion().equals(seccion))
                .toList();
    }

    @Override
    public List<DocChunk> vecinosEnSeccion(String docId, String seccion, int orden, int radio) {
        // Filtro dirigido por rango de 'orden' (índice int): trae solo los núcleos [orden-radio, orden+radio]
        // del documento, no la sección entera. El match exacto de sección se afina en Java (ver seccionCompleta).
        WhereFilter filtro = WhereFilter.builder()
                .operator(Operator.And)
                .operands(new WhereFilter[]{
                        WhereFilter.builder().path("docId").operator(Operator.Equal).valueText(docId).build(),
                        WhereFilter.builder().path("orden").operator(Operator.GreaterThanEqual)
                                .valueInt(orden - radio).build(),
                        WhereFilter.builder().path("orden").operator(Operator.LessThanEqual)
                                .valueInt(orden + radio).build()
                })
                .build();
        SortArgument porOrden = SortArgument.builder()
                .path(new String[]{"orden"})
                .order(SortOrder.asc)
                .build();

        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(props.className())
                .withWhere(WhereArgument.builder().filter(filtro).build())
                .withSort(porOrden)
                .withFields(
                        Field.builder().name("texto").build(),
                        Field.builder().name("titulo").build(),
                        Field.builder().name("fuente").build(),
                        Field.builder().name("seccion").build(),
                        Field.builder().name("url").build(),
                        Field.builder().name("origen").build(),
                        Field.builder().name("docId").build(),
                        Field.builder().name("seccionChars").build(),
                        Field.builder().name("orden").build())
                .run();
        failOnError("recuperando vecinos de " + docId, result);

        return parse(result.getResult()).stream()
                .filter(c -> c.seccion().equals(seccion))
                .toList();
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
            String titulo = str(obj.get("titulo"));
            String fuente = str(obj.get("fuente"));
            String seccion = str(obj.get("seccion"));
            String url = str(obj.get("url"));
            String origen = str(obj.get("origen"));
            String docId = str(obj.get("docId"));
            int seccionChars = intOf(obj.get("seccionChars"));
            int orden = intOf(obj.get("orden"));
            double score = scoreOf((Map<String, Object>) obj.get("_additional"));
            chunks.add(new DocChunk(texto, titulo, fuente, seccion, url, origen, score, docId, seccionChars, orden));
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

    /** Lee un entero de Weaviate (los números llegan como Double en el JSON). 0 si falta. */
    private static int intOf(Object value) {
        Double d = toDouble(value);
        return d == null ? 0 : d.intValue();
    }

    // -------------------------------------------------------------- helpers

    private static void esperarSegundos(long segundos) {
        try {
            Thread.sleep(segundos * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ingesta interrumpida esperando el retry de Gemini", e);
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

    private static void failOnError(String accion, Result<?> result) {
        if (result.hasErrors()) {
            throw new IllegalStateException("Weaviate falló " + accion + ": " + result.getError());
        }
    }
}
