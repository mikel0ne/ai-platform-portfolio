package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.github.GitHubGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fuente de documentación desde un repositorio de GitHub: en vez de clonar (ver histórico, JGit), usa la
 * API REST de GitHub para listar el árbol de una rama y descargar cada {@code *.md} bajo demanda.
 * Construye <b>permalinks a la rama</b> (no a un commit, para que se vean como un link normal de GitHub).
 * El {@code docId} es {@code <repo>:<path>}.
 *
 * <p>No implementa {@link FuenteDocumental}: a diferencia de las demás fuentes (config estática), el repo
 * a ingestar se decide en cada llamada — ver {@code POST /admin/ingest/git}.
 */
@Component
public class GitHubMarkdownSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubMarkdownSource.class);

    private final GitHubGateway github;

    public GitHubMarkdownSource(GitHubGateway github) {
        this.github = github;
    }

    /**
     * Repositorio Git del que ingestar documentación markdown, recibido en la llamada (no en config).
     *
     * @param url     URL del repositorio (HTTPS)
     * @param branch  rama a leer; vacío/{@code null} para que se detecte la rama por defecto del repo
     * @param subdir  subdirectorio al que limitar la búsqueda de {@code *.md} ({@code null}/vacío = todo el repo)
     * @param webBase base web para construir permalinks (p. ej. {@code https://github.com/org/repo}); si falta,
     *                se deriva de {@code url} quitando el sufijo {@code .git}
     */
    public record GitRepo(String url, String branch, String subdir, String webBase) {}

    /** Resuelve la rama del repo: la indicada o, si falta, la rama por defecto que reporta GitHub. */
    public GitRepo resolver(GitRepo repo) {
        if (repo.branch() != null && !repo.branch().isBlank()) {
            return repo;
        }
        String[] ownerRepo = ownerRepo(repo.url());
        String rama = github.ramaPorDefecto(ownerRepo[0], ownerRepo[1]);
        if (rama == null || rama.isBlank()) {
            throw new IllegalArgumentException("No se ha podido determinar la rama por defecto de " + repo.url());
        }
        return new GitRepo(repo.url(), rama, repo.subdir(), repo.webBase());
    }

    /** Lee todos los {@code *.md} del repo (resolviendo la rama si no se indicó). */
    public List<Documento> leer(GitRepo repoEntrada) {
        GitRepo repo = resolver(repoEntrada);
        String[] ownerRepo = ownerRepo(repo.url());
        String owner = ownerRepo[0];
        String repoNombre = ownerRepo[1];
        String webBase = webBase(repo);
        String subdirPrefix = repo.subdir() == null || repo.subdir().isBlank() ? "" : repo.subdir() + "/";

        List<String> rutas = github.ficherosMarkdown(owner, repoNombre, repo.branch());

        List<Documento> docs = new ArrayList<>();
        for (String relPath : rutas) {
            if (!subdirPrefix.isEmpty() && !relPath.startsWith(subdirPrefix)) {
                continue;
            }
            String contenido = github.contenido(owner, repoNombre, relPath, repo.branch());
            String docId = repoNombre + ":" + relPath;
            docs.add(new Documento(
                    docId,
                    LocalMarkdownSource.titulo(contenido, relPath),
                    relPath,
                    "git",
                    permalink(webBase, repo.branch(), relPath),
                    contenido));
        }
        log.info("GitHub: {} documentos markdown desde {}@{}", docs.size(), repo.url(), repo.branch());
        return docs;
    }

    // ------------------------------------------------------- helpers puros (testeables sin red)

    /** Base web del repo: la configurada o, si falta, la URL sin el sufijo {@code .git}. */
    static String webBase(GitRepo repo) {
        if (repo.webBase() != null && !repo.webBase().isBlank()) {
            return quitarBarraFinal(repo.webBase());
        }
        return quitarBarraFinal(sinSufijoGit(repo.url() == null ? "" : repo.url()));
    }

    /** Permalink a un fichero anclado a la rama: {@code <webBase>/blob/<branch>/<path>}. */
    static String permalink(String webBase, String branch, String relPath) {
        if (webBase == null || webBase.isBlank()) {
            return "";
        }
        return webBase + "/blob/" + branch + "/" + relPath;
    }

    /** {@code [owner, repo]} a partir de una URL tipo {@code https://github.com/<owner>/<repo>[.git]}. */
    static String[] ownerRepo(String url) {
        String limpio = sinSufijoGit(quitarBarraFinal(url == null ? "" : url));
        int slash = limpio.lastIndexOf('/');
        int slashPrevio = slash > 0 ? limpio.lastIndexOf('/', slash - 1) : -1;
        if (slash < 0 || slashPrevio < 0) {
            throw new IllegalArgumentException("URL de GitHub inválida (esperado owner/repo): " + url);
        }
        return new String[]{limpio.substring(slashPrevio + 1, slash), limpio.substring(slash + 1)};
    }

    private static String sinSufijoGit(String url) {
        return url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
    }

    private static String quitarBarraFinal(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
