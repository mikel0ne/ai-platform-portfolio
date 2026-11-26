package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SessionUsers {

    public static final String HEADER = "X-User-Id";

    private final UserRepository userRepository;

    public String requireKnown(String userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new UnknownUserException(userId);
        }
        return userId;
    }
}
