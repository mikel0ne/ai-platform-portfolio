package io.github.mikel0ne.autoops.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores de la API REST (el chat de búsqueda) en formato problem+json (RFC 7807). Nunca se filtran
 * detalles internos (stacktraces, mensajes de infraestructura) al cliente: se loguean con contexto y
 * se responde un mensaje genérico.
 *
 * <p>No cubre el transporte MCP, que tiene su propio formato de error gestionado por Spring AI.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Petición inválida");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Error no controlado procesando la petición", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error procesando la petición");
    }
}
