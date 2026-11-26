package io.github.mikel0ne.l1copilot.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Component
@Slf4j
public class TokenSigner {

    private static final String HMAC = "HmacSHA256";
    private static final int GENERATED_SECRET_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public TokenSigner(@Value("${copilot.auth.token-secret:}") String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            secret = new byte[GENERATED_SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
            log.warn("copilot.auth.token-secret is not set: using a random secret, demo tokens do not survive a restart");
        } else {
            secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String sign(String userId) {
        byte[] user = userId.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(user) + "." + ENCODER.encodeToString(hmac(user));
    }

    public Optional<String> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            byte[] user = DECODER.decode(token.substring(0, dot));
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(user), signature)) {
                return Optional.empty();
            }
            return Optional.of(new String(user, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
