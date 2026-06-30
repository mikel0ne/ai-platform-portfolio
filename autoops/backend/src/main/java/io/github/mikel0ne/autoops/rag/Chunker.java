package io.github.mikel0ne.autoops.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import io.github.mikel0ne.autoops.rag.source.Documento;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trocea un documento markdown en {@link Chunk}s. Estrategia <b>híbrida heading + recursivo</b> (decisión de
 * diseño del RAG):
 * <ol>
 *   <li><b>Por encabezado:</b> parte en secciones siguiendo los {@code #}, manteniendo el <i>breadcrumb</i>
 *       completo (p. ej. {@code Operación > Probes > Readiness}) como metadato {@code seccion}.</li>
 *   <li><b>Empaquetado de párrafos:</b> dentro de cada sección agrupa párrafos ({@code \n\n}) hasta
 *       {@code maxChars}.</li>
 *   <li><b>Recursivo:</b> si un solo párrafo excede {@code maxChars}, lo subdivide con el splitter recursivo de
 *       LangChain4j (corta por frases/palabras) en vez de emitir un chunk gigante.</li>
 * </ol>
 *
 * <p>No se hornea ninguna ventana de vecinos: cada chunk almacena su núcleo tal cual. El contexto extra
 * (sección completa) lo resuelve el bucle CRAG en consulta. En su lugar se estampa en cada chunk
 * {@code seccionChars} (tamaño total de su sección), para que el CRAG decida si expandir sin consultar.
 *
 * <p>Es {@code static} y puro: testeable sin Weaviate, sin red y sin LLM.
 */
public final class Chunker {

    private Chunker() {
    }

    /** Núcleo intermedio antes de calcular la ventana: el texto del fragmento y su breadcrumb de sección. */
    private record Nucleo(String texto, String seccion) {
    }

    public static List<Chunk> trocear(Documento doc, int maxChars, int overlapChars) {
        List<Nucleo> nucleos = extraerNucleos(doc, maxChars, overlapChars);
        return construirChunks(doc, nucleos);
    }

    // ------------------------------------------------------- 1) secciones + 2/3) empaquetado/recursivo

    private static List<Nucleo> extraerNucleos(Documento doc, int maxChars, int overlapChars) {
        List<Nucleo> nucleos = new ArrayList<>();
        // El breadcrumb arranca con el título del documento; cada heading lo refina por nivel.
        Deque<int[]> niveles = new ArrayDeque<>();   // pila de niveles de heading abiertos
        List<String> titulos = new ArrayList<>();    // títulos paralelos a la pila
        String seccionRaiz = doc.titulo() == null || doc.titulo().isBlank() ? "(inicio)" : doc.titulo();
        StringBuilder body = new StringBuilder();
        String[] breadcrumb = {seccionRaiz};

        for (String line : doc.contenido().split("\n", -1)) {
            int nivel = nivelHeading(line);
            if (nivel > 0) {
                flushSeccion(breadcrumb[0], body.toString(), maxChars, overlapChars, nucleos);
                body.setLength(0);
                // Cierra las secciones de nivel >= al actual y abre la nueva.
                while (!niveles.isEmpty() && niveles.peek()[0] >= nivel) {
                    niveles.pop();
                    titulos.remove(titulos.size() - 1);
                }
                niveles.push(new int[]{nivel});
                titulos.add(line.replaceFirst("^#+\\s*", "").trim());
                breadcrumb[0] = String.join(" > ", titulos);
            } else {
                body.append(line).append('\n');
            }
        }
        flushSeccion(breadcrumb[0], body.toString(), maxChars, overlapChars, nucleos);
        return nucleos;
    }

    /** Empaqueta los párrafos de una sección en núcleos de hasta {@code maxChars}, subdividiendo los gigantes. */
    private static void flushSeccion(String seccion, String body, int maxChars, int overlapChars,
                                     List<Nucleo> out) {
        String limpio = body.strip();
        if (limpio.isEmpty()) {
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : limpio.split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > maxChars) {
                // Párrafo más grande que el objetivo: cierra lo acumulado y subdivide recursivamente.
                if (current.length() > 0) {
                    out.add(new Nucleo(current.toString().strip(), seccion));
                    current.setLength(0);
                }
                for (String pieza : recursivo(p, maxChars, overlapChars)) {
                    out.add(new Nucleo(pieza.strip(), seccion));
                }
                continue;
            }
            if (current.length() > 0 && current.length() + p.length() + 2 > maxChars) {
                out.add(new Nucleo(current.toString().strip(), seccion));
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }
        if (current.length() > 0) {
            out.add(new Nucleo(current.toString().strip(), seccion));
        }
    }

    /** Subdivisión recursiva de un párrafo demasiado largo (frases → palabras), con solape. */
    private static List<String> recursivo(String texto, int maxChars, int overlapChars) {
        DocumentSplitter splitter = DocumentSplitters.recursive(maxChars, Math.min(overlapChars, maxChars - 1));
        return splitter.split(Document.from(texto)).stream().map(TextSegment::text).toList();
    }

    // ------------------------------------------------------- 4) ensamblado de chunks + seccionChars

    private static List<Chunk> construirChunks(Documento doc, List<Nucleo> nucleos) {
        // Tamaño total de cada sección (suma de longitudes de sus núcleos): lo lee el bucle CRAG para
        // decidir si expandir un fragmento a su sección completa sin consultar Weaviate (auto-merging).
        Map<String, Integer> charsPorSeccion = new HashMap<>();
        for (Nucleo n : nucleos) {
            charsPorSeccion.merge(n.seccion(), n.texto().length(), Integer::sum);
        }
        List<Chunk> chunks = new ArrayList<>(nucleos.size());
        for (int i = 0; i < nucleos.size(); i++) {
            Nucleo cur = nucleos.get(i);
            chunks.add(new Chunk(
                    cur.texto(),
                    doc.docId(),
                    doc.titulo(),
                    doc.fuente(),
                    cur.seccion(),
                    urlConAncla(doc.urlBase(), cur.seccion()),
                    doc.origen(),
                    i,
                    sha256(cur.texto()),
                    charsPorSeccion.get(cur.seccion())));
        }
        return chunks;
    }

    // ------------------------------------------------------- helpers

    /** Nivel de un heading markdown ({@code #}=1, {@code ##}=2...) o 0 si la línea no es un heading. */
    private static int nivelHeading(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        // Debe haber al menos un '#' seguido de espacio para ser un heading ATX válido.
        return (n > 0 && n < line.length() && line.charAt(n) == ' ') ? n : 0;
    }

    /** Construye el enlace con ancla a la sección (estilo GitHub). Vacío si el documento no es navegable. */
    static String urlConAncla(String urlBase, String breadcrumb) {
        if (urlBase == null || urlBase.isBlank()) {
            return "";
        }
        String ultimo = breadcrumb.contains(" > ")
                ? breadcrumb.substring(breadcrumb.lastIndexOf(" > ") + 3)
                : breadcrumb;
        String ancla = slug(ultimo);
        return ancla.isBlank() ? urlBase : urlBase + "#" + ancla;
    }

    /** Slug estilo GitHub: minúsculas, espacios→guiones, solo alfanumérico y guiones. */
    private static String slug(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .strip()
                .replaceAll("\\s+", "-");
    }

    /** Hash hex del núcleo (SHA-256). Estable e independiente de la plataforma. */
    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e); // imposible en una JVM estándar
        }
    }
}
