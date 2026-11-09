package io.github.mikel0ne.autoops.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Service
public class GitHubGateway {

    private final RestClient client;

    public GitHubGateway(RestClient gitHubClient) {
        this.client = gitHubClient;
    }

    public String defaultBranch(String owner, String repo) {
        RepoInfo info = client.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(RepoInfo.class);
        return info == null ? null : info.defaultBranch();
    }

    public String latestCommitSha(String owner, String repo, String branch) {
        CommitRef ref = client.get()
                .uri("/repos/{owner}/{repo}/commits/{branch}", owner, repo, branch)
                .retrieve()
                .body(CommitRef.class);
        return ref == null ? null : ref.sha();
    }

    public List<String> markdownFiles(String owner, String repo, String sha) {
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

    public String content(String owner, String repo, String path, String ref) {
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

        byte[] raw = Base64.getMimeDecoder().decode(resp.content());
        return new String(raw, StandardCharsets.UTF_8);
    }

    record RepoInfo(@JsonProperty("default_branch") String defaultBranch) {}

    record CommitRef(String sha) {}

    record TreeResponse(List<TreeEntry> tree) {}

    record TreeEntry(String path, String type) {}

    record ContentResponse(String content, String encoding) {}
}
