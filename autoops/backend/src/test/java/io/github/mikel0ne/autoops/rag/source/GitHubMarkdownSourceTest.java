package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.github.GitHubGateway;
import io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource.GitRepo;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubMarkdownSourceTest {

    @Test
    void derives_web_base_by_stripping_git_suffix() {
        GitRepo repo = new GitRepo("https://github.com/org/repo.git", "main", null, null);
        assertThat(GitHubMarkdownSource.webBase(repo)).isEqualTo("https://github.com/org/repo");
    }

    @Test
    void keeps_explicit_web_base_when_configured() {
        GitRepo repo = new GitRepo("https://git.local/x.git", "main", null, "https://github.com/org/repo/");
        assertThat(GitHubMarkdownSource.webBase(repo)).isEqualTo("https://github.com/org/repo");
    }

    @Test
    void permalink_anchors_to_the_branch() {
        String url = GitHubMarkdownSource.permalink("https://github.com/org/repo", "main", "docs/guia.md");
        assertThat(url).isEqualTo("https://github.com/org/repo/blob/main/docs/guia.md");
    }

    @Test
    void without_web_base_the_permalink_is_empty() {
        assertThat(GitHubMarkdownSource.permalink("", "main", "x.md")).isEmpty();
    }

    @Test
    void extracts_owner_and_repo_from_url() {
        assertThat(GitHubMarkdownSource.ownerRepo("https://github.com/mikel0ne/langchain.git"))
                .containsExactly("mikel0ne", "langchain");
    }

    @Test
    void extracts_owner_and_repo_without_git_suffix_or_trailing_slash() {
        assertThat(GitHubMarkdownSource.ownerRepo("https://github.com/org/repo/"))
                .containsExactly("org", "repo");
    }

    @Test
    void url_without_recognisable_owner_repo_throws() {
        assertThatThrownBy(() -> GitHubMarkdownSource.ownerRepo("repo-sin-barras"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveRepo_keeps_given_branch_without_calling_github() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubMarkdownSource source = new GitHubMarkdownSource(new GitHubGateway(builder.build()));

        GitRepo repo = new GitRepo("https://github.com/org/repo.git", "develop", null, null);
        assertThat(source.resolveRepo(repo)).isEqualTo(repo);
        server.verify();
    }

    @Test
    void resolveRepo_detects_default_branch_when_missing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo"))
                .andRespond(withSuccess("{\"default_branch\":\"develop\"}", MediaType.APPLICATION_JSON));
        GitHubMarkdownSource source = new GitHubMarkdownSource(new GitHubGateway(builder.build()));

        GitRepo repo = new GitRepo("https://github.com/org/repo.git", null, null, null);
        assertThat(source.resolveRepo(repo).branch()).isEqualTo("develop");
        server.verify();
    }
}
