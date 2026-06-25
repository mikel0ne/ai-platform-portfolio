package io.github.mikel0ne.autoops.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Indexa el corpus en Weaviate: lee los {@code *.md}, los trocea en chunks por sección con solape, calcula
 * sus embeddings y los inserta (doc 03 §F1, doc 05 §4). El troceado es estático y sin estado para poder
 * testearlo sin red ni LLM.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final WeaviateGateway gateway;
    private final IngestionProperties props;

    public IngestionService(WeaviateGateway gateway, IngestionProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    /** Asegura el esquema y reindexa el corpus completo desde cero. Devuelve el nº de chunks insertados. */
    public int reindex() {
        gateway.dropSchema();
        gateway.ensureSchema();
        return ingestAll();
    }

    /** Asegura el esquema e ingesta el corpus (sin borrar lo existente). Devuelve el nº de chunks insertados. */
    public int ingestAll() {
        gateway.ensureSchema();
        List<WeaviateGateway.Doc> docs = readCorpus();
        if (docs.isEmpty()) {
            log.warn("No se encontraron documentos en {}", props.corpusPath());
            return 0;
        }
        int n = gateway.ingest(docs);
        log.info("Ingesta completada: {} chunks desde {}", n, props.corpusPath());
        return n;
    }

    private List<WeaviateGateway.Doc> readCorpus() {
        Path dir = Path.of(props.corpusPath());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("La ruta del corpus no es un directorio: " + dir.toAbsolutePath());
        }
        List<WeaviateGateway.Doc> docs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> markdownFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : markdownFiles) {
                String fuente = file.getFileName().toString();
                String contenido = Files.readString(file, StandardCharsets.UTF_8);
                docs.addAll(chunkDocument(fuente, contenido, props.maxChars(), props.overlapChars()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error leyendo el corpus en " + dir, e);
        }
        return docs;
    }

    /**
     * Trocea un markdown en chunks: divide por secciones ({@code ##}) y, dentro de cada sección, agrupa
     * párrafos hasta {@code maxChars} con un solape de {@code overlapChars} entre chunks consecutivos.
     * Es {@code static} y puro: testeable sin Weaviate ni LLM.
     */
    static List<WeaviateGateway.Doc> chunkDocument(String fuente, String markdown, int maxChars, int overlapChars) {
        List<WeaviateGateway.Doc> chunks = new ArrayList<>();
        String seccion = "(inicio)";
        StringBuilder body = new StringBuilder();
        int[] orden = {0};

        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("#")) {
                flushSection(fuente, seccion, body.toString(), maxChars, overlapChars, chunks, orden);
                body.setLength(0);
                seccion = line.replaceFirst("^#+\\s*", "").trim();
            } else {
                body.append(line).append('\n');
            }
        }
        flushSection(fuente, seccion, body.toString(), maxChars, overlapChars, chunks, orden);
        return chunks;
    }

    private static void flushSection(String fuente, String seccion, String body, int maxChars,
                                     int overlapChars, List<WeaviateGateway.Doc> out, int[] orden) {
        String[] paragraphs = body.strip().split("\n\\s*\n");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + p.length() + 2 > maxChars) {
                String texto = current.toString().strip();
                out.add(new WeaviateGateway.Doc(texto, fuente, seccion, orden[0]++));
                current = new StringBuilder(tail(texto, overlapChars));
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }
        if (current.toString().strip().length() > 0) {
            out.add(new WeaviateGateway.Doc(current.toString().strip(), fuente, seccion, orden[0]++));
        }
    }

    /** Cola de un texto (~{@code n} caracteres) para dar continuidad al siguiente chunk. */
    private static String tail(String texto, int n) {
        if (n <= 0 || texto.length() <= n) {
            return texto.length() <= n ? texto + "\n\n" : "";
        }
        return texto.substring(texto.length() - n) + "\n\n";
    }
}
