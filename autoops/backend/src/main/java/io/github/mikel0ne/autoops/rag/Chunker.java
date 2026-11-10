package io.github.mikel0ne.autoops.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import io.github.mikel0ne.autoops.rag.source.SourceDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Chunker {

    private Chunker() {
    }

    private record CoreChunk(String text, String section) {
    }

    public static List<Chunk> splitDocument(SourceDocument doc, int maxChars, int overlapChars) {
        List<CoreChunk> cores = extractCores(doc, maxChars, overlapChars);
        return buildChunks(doc, cores);
    }

    private static List<CoreChunk> extractCores(SourceDocument doc, int maxChars, int overlapChars) {
        List<CoreChunk> cores = new ArrayList<>();

        Deque<Integer> levels = new ArrayDeque<>();
        List<String> titles = new ArrayList<>();
        String breadcrumb = doc.title() == null || doc.title().isBlank() ? "(inicio)" : doc.title();
        StringBuilder body = new StringBuilder();
        boolean inFence = false;

        for (String line : doc.content().split("\n", -1)) {
            // A '#' inside a fenced block is a shell comment, not a heading.
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) {
                inFence = !inFence;
            }
            int level = inFence ? 0 : headingLevel(line);
            if (level > 0) {
                flushSection(breadcrumb, body.toString(), maxChars, overlapChars, cores);
                body.setLength(0);

                while (!levels.isEmpty() && levels.peek() >= level) {
                    levels.pop();
                    titles.remove(titles.size() - 1);
                }
                levels.push(level);
                titles.add(line.replaceFirst("^#+\\s*", "").trim());
                breadcrumb = String.join(" > ", titles);
            } else {
                body.append(line).append('\n');
            }
        }
        flushSection(breadcrumb, body.toString(), maxChars, overlapChars, cores);
        return cores;
    }

    private static void flushSection(String section, String body, int maxChars, int overlapChars,
                                     List<CoreChunk> out) {
        String clean = body.strip();
        if (clean.isEmpty()) {
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : clean.split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > maxChars) {
                if (current.length() > 0) {
                    out.add(new CoreChunk(current.toString().strip(), section));
                    current.setLength(0);
                }
                for (String piece : recursive(p, maxChars, overlapChars)) {
                    out.add(new CoreChunk(piece.strip(), section));
                }
                continue;
            }
            if (current.length() > 0 && current.length() + p.length() + 2 > maxChars) {
                out.add(new CoreChunk(current.toString().strip(), section));
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }
        if (current.length() > 0) {
            out.add(new CoreChunk(current.toString().strip(), section));
        }
    }

    private static List<String> recursive(String text, int maxChars, int overlapChars) {
        DocumentSplitter splitter = DocumentSplitters.recursive(maxChars, Math.min(overlapChars, maxChars - 1));
        return splitter.split(Document.from(text)).stream().map(TextSegment::text).toList();
    }

    private static List<Chunk> buildChunks(SourceDocument doc, List<CoreChunk> cores) {
        Map<String, Integer> charsBySection = new HashMap<>();
        for (CoreChunk n : cores) {
            charsBySection.merge(n.section(), n.text().length(), Integer::sum);
        }
        List<Chunk> chunks = new ArrayList<>(cores.size());
        for (int i = 0; i < cores.size(); i++) {
            CoreChunk cur = cores.get(i);
            chunks.add(new Chunk(
                    cur.text(),
                    doc.docId(),
                    doc.title(),
                    doc.source(),
                    cur.section(),
                    urlWithAnchor(doc.baseUrl(), cur.section()),
                    doc.origin(),
                    i,
                    sha256(cur.text()),
                    charsBySection.get(cur.section())));
        }
        return chunks;
    }

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }

        return (n > 0 && n < line.length() && line.charAt(n) == ' ') ? n : 0;
    }

    static String urlWithAnchor(String baseUrl, String breadcrumb) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String last = breadcrumb.contains(" > ")
                ? breadcrumb.substring(breadcrumb.lastIndexOf(" > ") + 3)
                : breadcrumb;
        String anchor = slug(last);
        return anchor.isBlank() ? baseUrl : baseUrl + "#" + anchor;
    }

    // GitHub-style anchor: keeps accented letters, so Spanish headings still link to their section.
    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s_-]", "")
                .strip()
                .replaceAll("\\s", "-");
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
