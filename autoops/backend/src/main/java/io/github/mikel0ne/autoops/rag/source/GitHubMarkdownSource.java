package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.github.GitHubGateway;
import io.github.mikel0ne.autoops.rag.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fuente de documentación desde un repositorio de GitHub: en vez de clonar (ver histórico, JGit), usa la
 * API REST de GitHub para listar el árbol de un commit y descargar cada {@code *.md} bajo demanda.
 * Construye <b>permalinks al commit</b> (no a la rama, que se mueve y rompería la cita). El {@code docId}
 * es {@code <repo>:<path>}.
 *
 * <p>Si no hay repos configurados ({@code autoops.ingestion.git} vacío) no hace ninguna llamada.
 */
@Component
public class GitHubMarkdownSource implements FuenteDocumental {

    private static final Logger log = LoggerFactory.getLogger(GitHubMarkdownSource.class);

    private final IngestionProperties props;
    private final GitHubGateway github;

    public GitHubMarkdownSource(IngestionProperties props, GitHubGateway github) {
        this.props = props;
        this.github = github;
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
        String[] ownerRepo = ownerRepo(repo.url());
        String owner = ownerRepo[0];
        String repoNombre = ownerRepo[1];
        String webBase = webBase(repo);
        String subdirPrefix = repo.subdir() == null || repo.subdir().isBlank() ? "" : repo.subdir() + "/";

        String sha = github.ultimoCommitSha(owner, repoNombre, repo.branch());
        List<String> rutas = github.ficherosMarkdown(owner, repoNombre, sha);

        List<Documento> docs = new ArrayList<>();
        for (String relPath : rutas) {
            if (!subdirPrefix.isEmpty() && !relPath.startsWith(subdirPrefix)) {
                continue;
            }
            String contenido = github.contenido(owner, repoNombre, relPath, sha);
            String docId = repoNombre + ":" + relPath;
            docs.add(new Documento(
                    docId,
                    LocalMarkdownSource.titulo(contenido, relPath),
                    relPath,
                    nombre(),
                    permalink(webBase, sha, relPath),
                    contenido));
        }
        log.info("GitHub: {} documentos markdown desde {}@{}", docs.size(), repo.url(), sha);
        return docs;
    }

    // ------------------------------------------------------- helpers puros (testeables sin red)

    /** Base web del repo: la configurada o, si falta, la URL sin el sufijo {@code .git}. */
    static String webBase(IngestionProperties.GitRepo repo) {
        if (repo.webBase() != null && !repo.webBase().isBlank()) {
            return quitarBarraFinal(repo.webBase());
        }
        return quitarBarraFinal(sinSufijoGit(repo.url() == null ? "" : repo.url()));
    }

    /** Permalink a un fichero anclado al commit: {@code <webBase>/blob/<sha>/<path>}. */
    static String permalink(String webBase, String sha, String relPath) {
        if (webBase == null || webBase.isBlank()) {
            return "";
        }
        return webBase + "/blob/" + sha + "/" + relPath;
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
