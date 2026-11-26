package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.dto.LoginRequest;
import io.github.mikel0ne.l1copilot.dto.LoginResponse;
import io.github.mikel0ne.l1copilot.security.TokenSigner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Simulated login with a signed demo token")
@RestController
@RequestMapping("/copilot")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final TokenSigner tokenSigner;

    @Operation(summary = "Exchanges a demo user id for a signed session token (no real authentication)")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        if (!userRepository.existsById(request.userId())) {
            throw new UnknownUserException(request.userId());
        }
        return new LoginResponse(tokenSigner.sign(request.userId()));
    }
}
