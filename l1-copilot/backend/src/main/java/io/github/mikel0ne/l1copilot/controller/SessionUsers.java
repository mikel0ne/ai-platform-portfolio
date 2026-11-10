package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class SessionUsers {

    public static final String HEADER = "X-User-Id";

    private final UserRepository userRepository;

    public SessionUsers(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String requireKnown(String userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new UnknownUserException(userId);
        }
        return userId;
    }
}
