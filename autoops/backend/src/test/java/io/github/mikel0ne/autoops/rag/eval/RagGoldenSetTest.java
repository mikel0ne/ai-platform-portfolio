package io.github.mikel0ne.autoops.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mikel0ne.autoops.rag.Chunk;
import io.github.mikel0ne.autoops.rag.Chunker;
import io.github.mikel0ne.autoops.rag.Confidence;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.eval.RetrievalMetrics.GoldenCase;
import io.github.mikel0ne.autoops.rag.source.SourceDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// Offline guard of the golden set: checks it against the repo's Markdown, chunked like the Git ingestion does.
class RagGoldenSetTest {

    private static final int MIN_CASES = 40;
    private static final int MIN_SECURITY_CASES = 6;
    private static final int MIN_CAPPED_CASES = 5;
    private static final int MAX_CHARS = 2000;
    private static final int OVERLAP_CHARS = 200;
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Set<String> NEGATIVE_CATEGORIES = Set.of("out-of-domain", "not-in-corpus");
    private static final Pattern SECRET = Pattern.compile(
            "AIza[0-9A-Za-z_-]{35}|-----BEGIN [A-Z ]*PRIVATE KEY-----|gh[pousr]_[0-9A-Za-z]{36}");

    private static List<GoldenCase> cases;
    private static Map<String, List<Chunk>> corpus;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = new ClassPathResource("rag-golden-set.json").getInputStream()) {
            cases = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
        corpus = chunkRepoMarkdown(repoRoot());
    }

    @Test
    void keeps_its_size_and_its_share_of_security_and_unanswerable_cases() {
        assertThat(cases).hasSizeGreaterThanOrEqualTo(MIN_CASES);
        assertThat(cases.stream().filter(GoldenCase::security).count()).isGreaterThanOrEqualTo(MIN_SECURITY_CASES);
        assertThat(cases.stream().filter(c -> !c.retrieval()).count()).isGreaterThanOrEqualTo(MIN_CAPPED_CASES);
    }

    @Test
    void queries_are_non_blank_and_unique() {
        Set<String> seen = new HashSet<>();
        for (GoldenCase c : cases) {
            assertThat(c.query()).as("query").isNotBlank();
            assertThat(seen.add(c.query().strip().toLowerCase(Locale.ROOT)))
                    .as("duplicated query: %s", c.query()).isTrue();
        }
    }

    @Test
    void every_case_has_a_known_category_and_every_category_has_cases() {
        Set<String> known = new HashSet<>(RetrievalMetrics.FUNCTIONAL_CATEGORIES);
        known.addAll(RetrievalMetrics.SECURITY_CATEGORIES);
        Set<String> used = cases.stream().map(GoldenCase::category).collect(Collectors.toSet());

        assertThat(used).as("categories in use").isSubsetOf(known);
        assertThat(used).as("categories without cases").containsAll(known);
    }

    @Test
    void every_case_expects_either_evidence_or_a_confidence_cap() {
        for (GoldenCase c : cases) {
            assertThat(c.retrieval()).as("source xor maxConfidence in: %s", c.query())
                    .isNotEqualTo(c.maxConfidence() != null);
            if (c.retrieval()) {
                assertThat(c.sections()).as("sections of: %s", c.query()).isNotEmpty();
            } else {
                assertThatCode(c::confidenceCap).as("maxConfidence of: %s", c.query()).doesNotThrowAnyException();
                assertThat(c.confidenceCap()).as("a HIGH cap checks nothing: %s", c.query())
                        .isNotEqualTo(Confidence.HIGH);
            }
            if (NEGATIVE_CATEGORIES.contains(c.category())) {
                assertThat(c.retrieval()).as("a negative case must not expect evidence: %s", c.query()).isFalse();
            }
        }
    }

    @Test
    void every_expected_source_is_exactly_one_markdown_file_of_the_repo() {
        cases.stream().filter(GoldenCase::retrieval).forEach(c ->
                assertThat(corpus.keySet().stream().filter(path -> path.endsWith(c.source())).toList())
                        .as("repo files matching source %s", c.source()).hasSize(1));
    }

    @Test
    void every_expected_section_exists_in_its_document() {
        cases.stream().filter(GoldenCase::retrieval).forEach(c -> {
            List<Chunk> chunks = chunksOf(c);
            for (String expected : c.sections()) {
                String needle = expected.toLowerCase(Locale.ROOT);
                assertThat(chunks).as("chunks of %s under section '%s'", c.source(), expected)
                        .anyMatch(chunk -> chunk.section().toLowerCase(Locale.ROOT).contains(needle));
            }
        });
    }

    @Test
    void new_cases_expect_sections_that_actually_narrow_the_document() {
        cases.stream().filter(c -> c.retrieval() && !"baseline".equals(c.category())).forEach(c -> {
            List<Chunk> chunks = chunksOf(c);
            long relevant = chunks.stream().filter(chunk -> c.matches(toDocChunk(chunk))).count();
            assertThat(relevant).as("chunks of %s matched by %s (of %d)", c.source(), c.sections(), chunks.size())
                    .isLessThan(chunks.size());
        });
    }

    @Test
    void the_indexed_markdown_holds_no_secret_values() {
        corpus.forEach((path, chunks) -> chunks.forEach(chunk ->
                assertThat(SECRET.matcher(chunk.text()).find()).as("secret-like value in %s", path).isFalse()));
    }

    private static List<Chunk> chunksOf(GoldenCase c) {
        return corpus.entrySet().stream()
                .filter(e -> e.getKey().endsWith(c.source()))
                .flatMap(e -> e.getValue().stream())
                .toList();
    }

    private static DocChunk toDocChunk(Chunk chunk) {
        return new DocChunk(chunk.text(), chunk.title(), chunk.source(),
                chunk.section(), "", "git", 1.0, chunk.docId(), 0, 0);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("autoops").resolve("corpus"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above %s", Path.of("").toAbsolutePath()).isNotNull();
        return dir;
    }

    private static Map<String, List<Chunk>> chunkRepoMarkdown(Path root) throws IOException {
        Map<String, List<Chunk>> byPath = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md") && Files.isRegularFile(p)).toList()) {
                Path rel = root.relativize(file);
                if (excluded(rel)) {
                    continue;
                }
                String relPath = rel.toString().replace('\\', '/');
                String content = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                Matcher h1 = H1.matcher(content);
                String title = h1.find() ? h1.group(1).trim() : relPath;
                SourceDocument doc = new SourceDocument("repo:" + relPath, title, relPath, "git", "", content);
                byPath.put(relPath, Chunker.splitDocument(doc, MAX_CHARS, OVERLAP_CHARS));
            }
        }
        return byPath;
    }

    // Build output, dependencies and dot-folders never reach the Git ingestion.
    private static boolean excluded(Path rel) {
        for (Path segment : rel) {
            String name = segment.toString();
            if (name.startsWith(".") || Set.of("node_modules", "target", "dist").contains(name)) {
                return true;
            }
        }
        return false;
    }
}
