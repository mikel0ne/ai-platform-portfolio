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

/** Lógica pura de la fuente GitHub (owner/repo, permalinks, derivación de la base web y resolución de rama). */
class GitHubMarkdownSourceTest {

    @Test
    void deriva_la_base_web_quitando_el_sufijo_git() {
        GitRepo repo = new GitRepo("https://github.com/org/repo.git", "main", null, null);
        assertThat(GitHubMarkdownSource.webBase(repo)).isEqualTo("https://github.com/org/repo");
    }

    @Test
    void respeta_la_base_web_explicita_si_se_configura() {
        GitRepo repo = new GitRepo("https://git.local/x.git", "main", null, "https://github.com/org/repo/");
        assertThat(GitHubMarkdownSource.webBase(repo)).isEqualTo("https://github.com/org/repo");
    }

    @Test
    void el_permalink_ancla_a_la_rama() {
        String url = GitHubMarkdownSource.permalink("https://github.com/org/repo", "main", "docs/guia.md");
        assertThat(url).isEqualTo("https://github.com/org/repo/blob/main/docs/guia.md");
    }

    @Test
    void sin_base_web_el_permalink_queda_vacio() {
        assertThat(GitHubMarkdownSource.permalink("", "main", "x.md")).isEmpty();
    }

    @Test
    void extrae_owner_y_repo_de_la_url() {
        assertThat(GitHubMarkdownSource.ownerRepo("https://github.com/mikel0ne/langchain.git"))
                .containsExactly("mikel0ne", "langchain");
    }

    @Test
    void extrae_owner_y_repo_sin_sufijo_git_ni_barra_final() {
        assertThat(GitHubMarkdownSource.ownerRepo("https://github.com/org/repo/"))
                .containsExactly("org", "repo");
    }

    @Test
    void url_sin_owner_repo_reconocibles_lanza() {
        assertThatThrownBy(() -> GitHubMarkdownSource.ownerRepo("repo-sin-barras"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolver_respeta_la_rama_si_viene_indicada_sin_llamar_a_github() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubMarkdownSource source = new GitHubMarkdownSource(new GitHubGateway(builder.build()));

        GitRepo repo = new GitRepo("https://github.com/org/repo.git", "develop", null, null);
        assertThat(source.resolver(repo)).isEqualTo(repo);
        server.verify();
    }

    @Test
    void resolver_detecta_la_rama_por_defecto_si_no_se_indica() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo"))
                .andRespond(withSuccess("{\"default_branch\":\"develop\"}", MediaType.APPLICATION_JSON));
        GitHubMarkdownSource source = new GitHubMarkdownSource(new GitHubGateway(builder.build()));

        GitRepo repo = new GitRepo("https://github.com/org/repo.git", null, null, null);
        assertThat(source.resolver(repo).branch()).isEqualTo("develop");
        server.verify();
    }
}
