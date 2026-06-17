package io.github.mikel0ne.l1copilot.dto;

/**
 * Usuario de demo para el selector de "login" simulado del frontend.
 *
 * @param formal true si al usuario se le trata de usted (lo decide el backend según
 *               la edad), para que el frontend ajuste el tono de sus textos
 */
public record UserDto(String id, String name, int age, String avatarUrl, boolean formal) {
}
