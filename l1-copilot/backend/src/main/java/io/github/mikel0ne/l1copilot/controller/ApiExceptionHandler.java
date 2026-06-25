package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores de la API en formato problem+json (RFC 7807). Nunca se filtran
 * detalles internos (stacktraces, mensajes de excepciones de infraestructura)
 * hacia el cliente: se loguean con contexto y se responde un mensaje genérico.
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

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Falta la cabecera de sesión " + ex.getHeaderName());
    }

    @ExceptionHandler(ChatController.UnknownUserException.class)
    ProblemDetail unknownUser(ChatController.UnknownUserException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Usuario no autenticado");
    }

    @ExceptionHandler(ConversationService.LlmUnavailableException.class)
    ProblemDetail llmUnavailable(ConversationService.LlmUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "El asistente no está disponible, inténtalo en unos minutos");
    }

    @ExceptionHandler(ConversationService.ConversationException.class)
    ProblemDetail conversationError(ConversationService.ConversationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error procesando el mensaje");
    }
}
