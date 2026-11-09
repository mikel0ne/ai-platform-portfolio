package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.dto.ChatRequest;
import io.github.mikel0ne.l1copilot.dto.ChatResponse;
import io.github.mikel0ne.l1copilot.dto.UserDto;
import io.github.mikel0ne.l1copilot.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Copilot", description = "Agente de soporte L1 autónomo (LangGraph4j)")
@RestController
@RequestMapping("/copilot")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ConversationService conversationService;
    private final UserRepository userRepository;
    private final io.github.mikel0ne.l1copilot.config.TreatmentPolicy treatmentPolicy;

    @Operation(summary = "Turno de conversación con el agente",
            description = "Envía un mensaje del usuario; el mismo conversationId mantiene el estado entre turnos")
    @PostMapping("/chat")
    public ChatResponse chat(@RequestHeader("X-User-Id") String userId,
                             @Valid @RequestBody ChatRequest request) {
        ensureKnownUser(userId);
        ConversationService.ChatResult result =
                conversationService.chat(userId, request.conversationId(), request.message());
        return new ChatResponse(request.conversationId(), result.reply(), result.intent(),
                result.resolved(), result.orders(), result.ordersSelectable(), result.tickets(),
                result.returns(), result.confirm());
    }

    @Operation(summary = "Usuarios de demo para el login simulado del frontend")
    @GetMapping("/users")
    public List<UserDto> demoUsers() {
        return userRepository.findAll().stream()
                .map(u -> new UserDto(u.getId(), u.getName(), u.getAge(),
                        u.getAvatarUrl(), treatmentPolicy.isFormal(u)))
                .toList();
    }

    private void ensureKnownUser(String userId) {
        if (!userRepository.existsById(userId)) {
            throw new UnknownUserException(userId);
        }
    }

    public static class UnknownUserException extends RuntimeException {
        public UnknownUserException(String userId) {
            super("Usuario no autenticado: " + userId);
        }
    }
}
