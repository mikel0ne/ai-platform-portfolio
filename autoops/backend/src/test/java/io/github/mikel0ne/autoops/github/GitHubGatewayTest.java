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

class GitHubGatewayTest {

    @Test
    void defaultBranch_reads_the_repo_default_branch() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo"))
                .andRespond(withSuccess("{\"default_branch\":\"main\"}", MediaType.APPLICATION_JSON));

        GitHubGateway gateway = new GitHubGateway(builder.build());

        assertThat(gateway.defaultBranch("org", "repo")).isEqualTo("main");
        server.verify();
    }

    @Test
    void markdownFiles_keeps_only_md_blobs_from_the_tree() {
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

        assertThat(gateway.markdownFiles("org", "repo", "abc123")).containsExactly("docs/guia.md");
        server.verify();
    }

    @Test
    void content_decodes_github_style_base64_with_line_breaks() {
        String originalText = "# Titulo\n\nContenido de prueba con ñ y á.";
        String base64 = Base64.getEncoder().encodeToString(originalText.getBytes(StandardCharsets.UTF_8));

        String base64WithLineBreaks = base64.substring(0, base64.length() / 2) + "\\n" + base64.substring(base64.length() / 2);

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/org/repo/contents/docs/guia.md?ref=abc123"))
                .andRespond(withSuccess("{\"content\":\"" + base64WithLineBreaks + "\"}", MediaType.APPLICATION_JSON));

        GitHubGateway gateway = new GitHubGateway(builder.build());

        assertThat(gateway.content("org", "repo", "docs/guia.md", "abc123")).isEqualTo(originalText);
        server.verify();
    }
}
