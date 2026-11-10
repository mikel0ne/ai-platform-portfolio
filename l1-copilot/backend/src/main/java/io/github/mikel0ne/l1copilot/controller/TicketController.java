package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.TicketRepository;
import io.github.mikel0ne.l1copilot.dto.TicketDetailDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tickets", description = "Support ticket detail")
@RestController
@RequestMapping("/copilot/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketRepository ticketRepository;
    private final SessionUsers sessionUsers;

    @Operation(summary = "Detail of one of the session user's support tickets")
    @GetMapping("/{id}")
    public TicketDetailDto ticket(@RequestHeader(SessionUsers.HEADER) String userId, @PathVariable Long id) {
        String owner = sessionUsers.requireKnown(userId);
        return ticketRepository.findByIdAndUserId(id, owner)
                .map(TicketDetailDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", id));
    }
}
