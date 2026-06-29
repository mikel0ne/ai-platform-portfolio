package io.github.mikel0ne.autoops.github;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Llamadas a la API de GitHub con fixtures (sin red real, mismo criterio que {@code LokiGatewayTest}). */
class GitHubGatewayTest {

    @Test
    void ultimoCommitSha_lee_el_sha_de_la_rama() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo/commits/main"))
                .andRespond(withSuccess("{\"sha\":\"abc123\"}", MediaType.APPLICATION_JSON));

        GitHubGateway gateway = new GitHubGateway(builder.build());

        assertThat(gateway.ultimoCommitSha("org", "repo", "main")).isEqualTo("abc123");
        server.verify();
    }

    @Test
    void ramaPorDefecto_lee_el_default_branch_del_repo() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo"))
                .andRespond(withSuccess("{\"default_branch\":\"main\"}", MediaType.APPLICATION_JSON));

        GitHubGateway gateway = new GitHubGateway(builder.build());

        assertThat(gateway.ramaPorDefecto("org", "repo")).isEqualTo("main");
        server.verify();
    }

    @Test
    void ficherosMarkdown_filtra_solo_blobs_md_del_arbol() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo/git/trees/abc123?recursive=1"))
                .andRespond(withSuccess("""
                        {"tree":[
                          {"path":"docs/guia.md","type":"blob"},
                          {"path":"src/App.java","type":"blob"},
                          {"path":"docs","type":"tree"}
                        ]}""", MediaType.APPLICATION_JSON));

        GitHubGateway gateway = new GitHubGateway(builder.build());

        assertThat(gateway.ficherosMarkdown("org", "repo", "abc123")).containsExactly("docs/guia.md");
        server.verify();
    }

    @Test
    void contenido_decodifica_base64_con_saltos_de_linea_estilo_github() {
        String textoOriginal = "# Titulo\n\nContenido de prueba con ñ y á.";
        String base64 = Base64.getEncoder().encodeToString(textoOriginal.getBytes(StandardCharsets.UTF_8));
        // GitHub corta el base64 cada 60 caracteres con '\n'; aquí simulamos un corte a mitad.
        String base64ConSaltos = base64.substring(0, base64.length() / 2) + "\\n" + base64.substring(base64.length() / 2);

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo/contents/docs/guia.md?ref=abc123"))
                .andRespond(withSuccess("{\"content\":\"" + base64ConSaltos + "\"}", MediaType.APPLICATION_JSON));

        GitHubGateway gateway = new GitHubGateway(builder.build());

        assertThat(gateway.contenido("org", "repo", "docs/guia.md", "abc123")).isEqualTo(textoOriginal);
        server.verify();
    }
}
