package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.rag.IngestionProperties.GitRepo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lógica pura de la fuente GitHub (owner/repo, permalinks y derivación de la base web), sin red. */
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
    void el_permalink_ancla_al_commit_no_a_la_rama() {
        String url = GitHubMarkdownSource.permalink("https://github.com/org/repo", "abc123", "docs/guia.md");
        assertThat(url).isEqualTo("https://github.com/org/repo/blob/abc123/docs/guia.md");
    }

    @Test
    void sin_base_web_el_permalink_queda_vacio() {
        assertThat(GitHubMarkdownSource.permalink("", "abc", "x.md")).isEmpty();
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
}
