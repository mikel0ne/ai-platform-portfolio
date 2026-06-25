package io.github.mikel0ne.l1copilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Usuario de demo. Existe solo para simular el login del frontend;
 * la autenticación real queda fuera de alcance del prototipo.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @NotBlank
    @Column(length = 20)
    private String id;

    @NotBlank
    @Column(nullable = false, length = 100)
    private String name;

    @NotBlank
    @Email
    @Column(nullable = false, length = 150)
    private String email;

    /** Edad del usuario: decide el tratamiento (tú/usted) del asistente. */
    @Positive
    @Column(nullable = false)
    private int age;

    /** Foto de perfil para el selector de "login" de la demo. */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;
}
