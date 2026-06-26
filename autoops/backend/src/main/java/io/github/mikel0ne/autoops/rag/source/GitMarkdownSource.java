package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.rag.IngestionProperties;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Fuente de documentación desde un repositorio Git: clona la rama indicada en un directorio temporal, lee los
 * {@code *.md} (opcionalmente bajo un subdirectorio) y construye <b>permalinks al commit</b> (no a la rama, que
 * se mueve y rompería la cita). El {@code docId} es {@code <repo>:<path>}.
 *
 * <p>Se clona por HTTPS con JGit (Java puro, sin depender del binario {@code git}). JGit 5.12 no soporta
 * shallow clone ({@code setDepth} llegó en 6.3): se clona una sola rama a profundidad completa, suficiente para
 * un repo de documentación. Para monorepos grandes, subir JGit a 6.3+ y añadir {@code setDepth(1)}.
 *
 * <p>Si no hay repos configurados ({@code autoops.ingestion.git} vacío) devuelve una lista vacía sin clonar.
 */
@Component
public class GitMarkdownSource implements FuenteDocumental {

    private static final Logger log = LoggerFactory.getLogger(GitMarkdownSource.class);

    private final IngestionProperties props;

    public GitMarkdownSource(IngestionProperties props) {
        this.props = props;
    }

    @Override
    public String nombre() {
        return "git";
    }

    @Override
    public List<Documento> leer() {
        List<IngestionProperties.GitRepo> repos = props.git();
        if (repos == null || repos.isEmpty()) {
            return List.of();
        }
        List<Documento> docs = new ArrayList<>();
        for (IngestionProperties.GitRepo repo : repos) {
            docs.addAll(leerRepo(repo));
        }
        return docs;
    }

    private List<Documento> leerRepo(IngestionProperties.GitRepo repo) {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("autoops-git-");
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio temporal para clonar " + repo.url(), e);
        }
        try (Git git = Git.cloneRepository()
                .setURI(repo.url())
                .setDirectory(tmp.toFile())
                .setBranch(repo.branch())
                .setCloneAllBranches(false)
                .call()) {

            String sha = git.getRepository().resolve("HEAD").getName();
            String webBase = webBase(repo);
            Path raiz = tmp.toAbsolutePath();
            Path base = repo.subdir() == null || repo.subdir().isBlank() ? raiz : raiz.resolve(repo.subdir());
            List<Documento> docs = new ArrayList<>();

            if (Files.isDirectory(base)) {
                try (Stream<Path> files = Files.walk(base)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                        String relPath = raiz.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
                        String contenido = Files.readString(file, StandardCharsets.UTF_8);
                        String docId = repoNombre(repo.url()) + ":" + relPath;
                        docs.add(new Documento(
                                docId,
                                LocalMarkdownSource.titulo(contenido, relPath),
                                relPath,
                                nombre(),
                                permalink(webBase, sha, relPath),
                                contenido));
                    }
                }
            }
            log.info("Git: {} documentos markdown desde {}@{}", docs.size(), repo.url(), sha);
            return docs;
        } catch (IOException e) {
            throw new UncheckedIOException("Error leyendo el repo clonado " + repo.url(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Error clonando " + repo.url() + " (rama " + repo.branch() + ")", e);
        } finally {
            borrarRecursivo(tmp);
        }
    }

    // ------------------------------------------------------- helpers puros (testeables sin red)

    /** Base web del repo: la configurada o, si falta, la URL de clonado sin el sufijo {@code .git}. */
    static String webBase(IngestionProperties.GitRepo repo) {
        if (repo.webBase() != null && !repo.webBase().isBlank()) {
            return quitarBarraFinal(repo.webBase());
        }
        String url = repo.url() == null ? "" : repo.url();
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        return quitarBarraFinal(url);
    }

    /** Permalink a un fichero anclado al commit: {@code <webBase>/blob/<sha>/<path>}. */
    static String permalink(String webBase, String sha, String relPath) {
        if (webBase == null || webBase.isBlank()) {
            return "";
        }
        return webBase + "/blob/" + sha + "/" + relPath;
    }

    static String repoNombre(String url) {
        if (url == null || url.isBlank()) {
            return "repo";
        }
        String u = quitarBarraFinal(url.endsWith(".git") ? url.substring(0, url.length() - 4) : url);
        int slash = u.lastIndexOf('/');
        return slash >= 0 && slash < u.length() - 1 ? u.substring(slash + 1) : u;
    }

    private static String quitarBarraFinal(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static void borrarRecursivo(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort: un temporal que no se borra no es crítico
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
