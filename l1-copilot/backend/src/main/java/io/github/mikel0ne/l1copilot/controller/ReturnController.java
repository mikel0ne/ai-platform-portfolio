package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.dto.ReturnLabelDto;
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

/**
 * Consulta de la etiqueta de devolución de un pedido para la página de detalle
 * del frontend ({@code /returns/{id}}). Es de solo lectura y pública (sin
 * cabecera {@code X-User-Id}): el enlace de la etiqueta debe poder abrirse
 * directamente en una ventana emergente, igual que el detalle de un ticket.
 */
@Tag(name = "Returns", description = "Etiqueta de devolución de un pedido")
@RestController
@RequestMapping("/copilot/returns")
@RequiredArgsConstructor
@Slf4j
public class ReturnController {

    private final OrderRepository orderRepository;

    @Operation(summary = "Etiqueta de devolución de un pedido por id")
    @GetMapping("/{id}")
    public ReturnLabelDto returnLabel(@PathVariable Long id) {
        log.debug("Consulta de la etiqueta de devolución del pedido {}", id);
        return orderRepository.findById(id)
                .map(ReturnLabelDto::from)
                .orElseThrow(() -> new ReturnNotFoundException(id));
    }

    /** No existe el pedido / devolución solicitada. */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ReturnNotFoundException extends RuntimeException {
        public ReturnNotFoundException(Long id) {
            super("No existe la devolución del pedido " + id);
        }
    }
}

