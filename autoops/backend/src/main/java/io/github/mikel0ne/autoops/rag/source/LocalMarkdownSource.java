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

@Component
public class LocalMarkdownSource implements DocumentSource {

    private static final Logger log = LoggerFactory.getLogger(LocalMarkdownSource.class);

    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+)$");

    private final IngestionProperties props;

    public LocalMarkdownSource(IngestionProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "markdown-local";
    }

    @Override
    public List<SourceDocument> read() {
        Path dir = Path.of(props.corpusPath());
        if (!Files.isDirectory(dir)) {
            log.info("Local source disabled: '{}' is not a directory", dir.toAbsolutePath());
            return List.of();
        }
        List<SourceDocument> docs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> markdown = files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : markdown) {
                String source = file.getFileName().toString();
                String content = Files.readString(file, StandardCharsets.UTF_8);
                docs.add(new SourceDocument(source, title(content, source), source, name(), "", content));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading the corpus at " + dir, e);
        }
        return docs;
    }

    static String title(String content, String fallback) {
        Matcher m = H1.matcher(content);
        return m.find() ? m.group(1).trim() : fallback;
    }
}
