package io.github.mikel0ne.autoops.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Cliente de la API REST de GitHub para leer documentación sin clonar el repositorio (a diferencia de un
 * clon JGit, cada llamada trae solo lo que hace falta: el SHA de la rama, el árbol de ficheros y el
 * contenido de un fichero concreto). Pensado para reutilizarse también desde una futura tool de lectura de
 * código bajo demanda (grep + leer fichero), sin pasar por un índice vectorial.
 */
@Service
public class GitHubGateway {

    private final RestClient client;

    public GitHubGateway(RestClient gitHubClient) {
        this.client = gitHubClient;
    }

    /** Rama por defecto del repositorio (la que GitHub muestra al entrar), para no tener que adivinarla. */
    public String ramaPorDefecto(String owner, String repo) {
        RepoInfo info = client.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(RepoInfo.class);
        return info == null ? null : info.defaultBranch();
    }

    /** SHA del último commit de una rama. Reservado para una futura tool de lectura de código bajo demanda. */
    public String ultimoCommitSha(String owner, String repo, String branch) {
        CommitRef ref = client.get()
                .uri("/repos/{owner}/{repo}/commits/{branch}", owner, repo, branch)
                .retrieve()
                .body(CommitRef.class);
        return ref == null ? null : ref.sha();
    }

    /** Rutas (relativas a la raíz del repo) de todos los {@code *.md} del árbol completo, en un commit dado. */
    public List<String> ficherosMarkdown(String owner, String repo, String sha) {
        TreeResponse tree = client.get()
                .uri("/repos/{owner}/{repo}/git/trees/{sha}?recursive=1", owner, repo, sha)
                .retrieve()
                .body(TreeResponse.class);
        if (tree == null || tree.tree() == null) {
            return List.of();
        }
        return tree.tree().stream()
                .filter(e -> "blob".equals(e.type()) && e.path() != null && e.path().endsWith(".md"))
                .map(TreeEntry::path)
                .sorted()
                .toList();
    }

    /** Contenido de texto de un fichero en un commit/rama concretos. */
    public String contenido(String owner, String repo, String path, String ref) {
        URI uri = UriComponentsBuilder.fromUriString("https://api.github.com")
                .pathSegment("repos", owner, repo, "contents")
                .pathSegment(path.split("/"))
                .queryParam("ref", ref)
                .build()
                .encode()
                .toUri();
        ContentResponse resp = client.get().uri(uri).retrieve().body(ContentResponse.class);
        if (resp == null || resp.content() == null) {
            return "";
        }
        // GitHub corta el base64 cada 60 caracteres con '\n'; el decoder MIME los tolera.
        byte[] raw = Base64.getMimeDecoder().decode(resp.content());
        return new String(raw, StandardCharsets.UTF_8);
    }

    record RepoInfo(@JsonProperty("default_branch") String defaultBranch) {}

    record CommitRef(String sha) {}

    record TreeResponse(List<TreeEntry> tree) {}

    record TreeEntry(String path, String type) {}

    record ContentResponse(String content, String encoding) {}
}
