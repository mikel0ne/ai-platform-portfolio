package io.github.mikel0ne.autoops.rag.source;

import io.github.mikel0ne.autoops.rag.IngestionProperties.GitRepo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Lógica pura de la fuente Git (construcción de permalinks y derivación de la base web), sin red. */
class GitMarkdownSourceTest {

    @Test
    void deriva_la_base_web_quitando_el_sufijo_git() {
        GitRepo repo = new GitRepo("https://github.com/org/repo.git", "main", null, null);
        assertThat(GitMarkdownSource.webBase(repo)).isEqualTo("https://github.com/org/repo");
    }

    @Test
    void respeta_la_base_web_explicita_si_se_configura() {
        GitRepo repo = new GitRepo("https://git.local/x.git", "main", null, "https://github.com/org/repo/");
        assertThat(GitMarkdownSource.webBase(repo)).isEqualTo("https://github.com/org/repo");
    }

    @Test
    void el_permalink_ancla_al_commit_no_a_la_rama() {
        String sha = "abc123";
        String url = GitMarkdownSource.permalink("https://github.com/org/repo", sha, "docs/guia.md");
        assertThat(url).isEqualTo("https://github.com/org/repo/blob/abc123/docs/guia.md");
    }

    @Test
    void sin_base_web_el_permalink_queda_vacio() {
        assertThat(GitMarkdownSource.permalink("", "abc", "x.md")).isEmpty();
    }

    @Test
    void extrae_el_nombre_del_repo_de_la_url() {
        assertThat(GitMarkdownSource.repoNombre("https://github.com/org/repo.git")).isEqualTo("repo");
        assertThat(GitMarkdownSource.repoNombre("https://git.local/grupo/sub/docs/")).isEqualTo("docs");
    }
}
