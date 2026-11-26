package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.security.TokenSigner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SessionUsers {

    public static final String HEADER = "Authorization";
    private static final String BEARER = "Bearer ";

    private final UserRepository userRepository;
    private final TokenSigner tokenSigner;

    public String requireKnown(String authorization) {
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).trim()
                : null;
        String userId = tokenSigner.verify(token).orElse(null);
        if (userId == null || !userRepository.existsById(userId)) {
            throw new UnknownUserException(userId);
        }
        return userId;
    }
}
