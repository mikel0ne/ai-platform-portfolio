package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.github.GitHubGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GitHubMarkdownSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubMarkdownSource.class);

    private final GitHubGateway github;

    public GitHubMarkdownSource(GitHubGateway github) {
        this.github = github;
    }

    public record GitRepo(String url, String branch, String subdir, String webBase) {}

    public GitRepo resolveRepo(GitRepo repo) {
        if (repo.branch() != null && !repo.branch().isBlank()) {
            return repo;
        }
        String[] ownerRepo = ownerRepo(repo.url());
        String branch = github.defaultBranch(ownerRepo[0], ownerRepo[1]);
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("Could not determine the default branch of " + repo.url());
        }
        return new GitRepo(repo.url(), branch, repo.subdir(), repo.webBase());
    }

    public List<SourceDocument> read(GitRepo repoInput) {
        GitRepo repo = resolveRepo(repoInput);
        String[] ownerRepo = ownerRepo(repo.url());
        String owner = ownerRepo[0];
        String repoName = ownerRepo[1];
        String webBase = webBase(repo);
        String subdirPrefix = repo.subdir() == null || repo.subdir().isBlank() ? "" : repo.subdir() + "/";

        List<String> paths = github.markdownFiles(owner, repoName, repo.branch());

        List<SourceDocument> docs = new ArrayList<>();
        for (String relPath : paths) {
            if (!subdirPrefix.isEmpty() && !relPath.startsWith(subdirPrefix)) {
                continue;
            }
            String content = github.content(owner, repoName, relPath, repo.branch());
            String docId = repoName + ":" + relPath;
            docs.add(new SourceDocument(
                    docId,
                    LocalMarkdownSource.title(content, relPath),
                    relPath,
                    "git",
                    permalink(webBase, repo.branch(), relPath),
                    content));
        }
        log.info("GitHub: {} markdown documents from {}@{}", docs.size(), repo.url(), repo.branch());
        return docs;
    }

    static String webBase(GitRepo repo) {
        if (repo.webBase() != null && !repo.webBase().isBlank()) {
            return stripTrailingSlash(repo.webBase());
        }
        return stripTrailingSlash(withoutGitSuffix(repo.url() == null ? "" : repo.url()));
    }

    static String permalink(String webBase, String branch, String relPath) {
        if (webBase == null || webBase.isBlank()) {
            return "";
        }
        return webBase + "/blob/" + branch + "/" + relPath;
    }

    static String[] ownerRepo(String url) {
        String clean = withoutGitSuffix(stripTrailingSlash(url == null ? "" : url));
        int slash = clean.lastIndexOf('/');
        int previousSlash = slash > 0 ? clean.lastIndexOf('/', slash - 1) : -1;
        if (slash < 0 || previousSlash < 0) {
            throw new IllegalArgumentException("Invalid GitHub URL (expected owner/repo): " + url);
        }
        return new String[]{clean.substring(previousSlash + 1, slash), clean.substring(slash + 1)};
    }

    private static String withoutGitSuffix(String url) {
        return url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
