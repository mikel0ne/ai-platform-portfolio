package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.config.TreatmentPolicy;
import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.dto.ChatRequest;
import io.github.mikel0ne.l1copilot.dto.ChatResponse;
import io.github.mikel0ne.l1copilot.dto.UserDto;
import io.github.mikel0ne.l1copilot.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Copilot", description = "Autonomous L1 support agent (LangGraph4j)")
@RestController
@RequestMapping("/copilot")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationService conversationService;
    private final UserRepository userRepository;
    private final TreatmentPolicy treatmentPolicy;
    private final SessionUsers sessionUsers;

    @Operation(summary = "One conversation turn with the agent",
            description = "Sends a user message; the same conversationId keeps the state across turns")
    @PostMapping("/chat")
    public ChatResponse chat(@RequestHeader(SessionUsers.HEADER) String userId,
                             @Valid @RequestBody ChatRequest request) {
        sessionUsers.requireKnown(userId);
        ConversationService.ChatResult result =
                conversationService.chat(userId, request.conversationId(), request.message());
        return new ChatResponse(request.conversationId(), result.reply(), result.intent(),
                result.resolved(), result.orders(), result.ordersSelectable(), result.tickets(),
                result.returns(), result.confirm());
    }

    @Operation(summary = "Demo users for the frontend's simulated login")
    @GetMapping("/users")
    public List<UserDto> demoUsers() {
        return userRepository.findAll().stream()
                .map(u -> new UserDto(u.getId(), u.getName(), u.getAge(),
                        u.getAvatarUrl(), treatmentPolicy.isFormal(u)))
                .toList();
    }
}
