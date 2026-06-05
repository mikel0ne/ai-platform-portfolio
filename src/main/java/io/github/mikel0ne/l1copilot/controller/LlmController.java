package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.service.LlmService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para interactuar con el LLM
 */
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
@Slf4j
public class LlmController {

    private final LlmService llmService;

    /**
     * Endpoint para enviar prompts al LLM
     *
     * @param request Body con el prompt
     * @return Respuesta del LLM
     */
    @PostMapping("/chat")
    public ResponseEntity<LlmResponse> chat(@RequestBody LlmRequest request) {
        log.info("Request recibido: {}", request.getPrompt());
        String response = llmService.chat(request.getPrompt());
        return ResponseEntity.ok(new LlmResponse(response));
    }

    /**
     * Endpoint para "Hola mundo"
     */
    @GetMapping("/hello")
    public ResponseEntity<LlmResponse> hello() {
        log.info("Hello endpoint llamado");
        String response = llmService.sayHello();
        return ResponseEntity.ok(new LlmResponse(response));
    }

    /**
     * DTO para request
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LlmRequest {
        private String prompt;
    }

    /**
     * DTO para response
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LlmResponse {
        private String response;
    }
}

