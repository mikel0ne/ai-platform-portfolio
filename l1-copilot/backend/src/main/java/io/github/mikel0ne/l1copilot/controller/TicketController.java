package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.TicketRepository;
import io.github.mikel0ne.l1copilot.dto.TicketDetailDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tickets", description = "Detalle de tickets de soporte")
@RestController
@RequestMapping("/copilot/tickets")
@RequiredArgsConstructor
@Slf4j
public class TicketController {

    private final TicketRepository ticketRepository;

    @Operation(summary = "Detalle de un ticket de soporte por id")
    @GetMapping("/{id}")
    public TicketDetailDto ticket(@PathVariable Long id) {
        log.debug("Ticket detail requested: {}", id);
        return ticketRepository.findById(id)
                .map(TicketDetailDto::from)
                .orElseThrow(() -> new TicketNotFoundException(id));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class TicketNotFoundException extends RuntimeException {
        public TicketNotFoundException(Long id) {
            super("No existe el ticket " + id);
        }
    }
}
