package io.github.mikel0ne.l1copilot.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Servicio para interactuar con el LLM de Google AI Studio (Gemini)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final ChatLanguageModel chatLanguageModel;
    private final FileCacheManager fileCacheManager;

    /**
     * Envía un prompt al LLM y devuelve la respuesta (con caché)
     *
     * @param prompt Texto del prompt
     * @return Respuesta del LLM
     */
    public String chat(String prompt) {
        String cacheKey = generateCacheKey(prompt);

        // Intenta obtener del caché
        Object cachedResponse = fileCacheManager.get(cacheKey);
        if (cachedResponse != null) {
            log.info("Respuesta obtenida del caché para prompt: {}", prompt);
            return (String) cachedResponse;
        }

        log.info("Enviando prompt al LLM: {}", prompt);
        String response = chatLanguageModel.generate(prompt);
        log.info("Respuesta recibida del LLM");

        // Almacena en caché
        fileCacheManager.put(cacheKey, response);

        return response;
    }

    /**
     * Realiza un "hola mundo" simple
     *
     * @return Respuesta del LLM
     */
    public String sayHello() {
        String prompt = "Di 'Hola mundo desde LangChain4j con Spring Boot' de forma breve";
        return chat(prompt);
    }

    /**
     * Genera una clave de caché a partir del prompt
     */
    private String generateCacheKey(String prompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(prompt.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            log.error("Error al generar clave de caché", e);
            return "prompt_" + prompt.hashCode();
        }
    }
}

