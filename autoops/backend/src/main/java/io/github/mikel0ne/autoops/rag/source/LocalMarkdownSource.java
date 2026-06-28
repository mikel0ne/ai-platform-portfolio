package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.rag.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fuente de documentación desde una carpeta local de markdown ({@code corpus/*.md}); opcional, sin efecto
 * si {@code corpusPath} no apunta a un directorio existente (fuente desactivada). El {@code docId}/
 * {@code fuente} es el nombre de fichero y no hay URL navegable.
 */
@Component
public class LocalMarkdownSource implements FuenteDocumental {

    private static final Logger log = LoggerFactory.getLogger(LocalMarkdownSource.class);

    /** Primer encabezado H1 del documento, usado como título legible si existe. */
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+)$");

    private final IngestionProperties props;

    public LocalMarkdownSource(IngestionProperties props) {
        this.props = props;
    }

    @Override
    public String nombre() {
        return "markdown-local";
    }

    @Override
    public List<Documento> leer() {
        Path dir = Path.of(props.corpusPath());
        if (!Files.isDirectory(dir)) {
            log.info("Fuente local desactivada: '{}' no es un directorio", dir.toAbsolutePath());
            return List.of();
        }
        List<Documento> docs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> markdown = files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : markdown) {
                String fuente = file.getFileName().toString();
                String contenido = Files.readString(file, StandardCharsets.UTF_8);
                docs.add(new Documento(fuente, titulo(contenido, fuente), fuente, nombre(), "", contenido));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error leyendo el corpus en " + dir, e);
        }
        return docs;
    }

    /** Título = primer H1 del markdown; si no hay, el nombre de fichero. */
    static String titulo(String contenido, String fallback) {
        Matcher m = H1.matcher(contenido);
        return m.find() ? m.group(1).trim() : fallback;
    }
}
