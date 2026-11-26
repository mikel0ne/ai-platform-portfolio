package io.github.mikel0ne.l1copilot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TokenSignerTest {

    private final TokenSigner signer = new TokenSigner("signer-test-secret");

    @Test
    @DisplayName("A signed token verifies back to the user id")
    void validTokenRoundTrips() {
        assertThat(signer.verify(signer.sign("u-001"))).contains("u-001");
    }

    @Test
    @DisplayName("A tampered signature is rejected")
    void tamperedSignatureIsRejected() {
        String token = signer.sign("u-001");
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertThat(signer.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("Swapping the user keeps the old signature and is rejected")
    void tamperedUserIsRejected() {
        String signature = signer.sign("u-001").split("\\.")[1];
        String forgedUser = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("u-002".getBytes(StandardCharsets.UTF_8));

        assertThat(signer.verify(forgedUser + "." + signature)).isEmpty();
    }

    @Test
    @DisplayName("A token signed with another secret is rejected")
    void otherSecretIsRejected() {
        assertThat(signer.verify(new TokenSigner("another-secret").sign("u-001"))).isEmpty();
    }

    @Test
    @DisplayName("Malformed, empty and null tokens are rejected")
    void malformedTokensAreRejected() {
        assertThat(signer.verify("no-dot")).isEmpty();
        assertThat(signer.verify(".")).isEmpty();
        assertThat(signer.verify("a.b.c")).isEmpty();
        assertThat(signer.verify("!!!.???")).isEmpty();
        assertThat(signer.verify("u-001.")).isEmpty();
        assertThat(signer.verify("")).isEmpty();
        assertThat(signer.verify(null)).isEmpty();
    }

    @Test
    @DisplayName("Without a configured secret a random one is used, so tokens differ between instances")
    void blankSecretGeneratesRandomOne() {
        TokenSigner first = new TokenSigner("");
        TokenSigner second = new TokenSigner(" ");

        assertThat(first.verify(first.sign("u-001"))).contains("u-001");
        assertThat(second.verify(first.sign("u-001"))).isEmpty();
    }
}
