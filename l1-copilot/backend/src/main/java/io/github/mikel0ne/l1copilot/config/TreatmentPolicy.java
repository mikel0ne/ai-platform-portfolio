package io.github.mikel0ne.l1copilot.config;

import io.github.mikel0ne.l1copilot.agent.Treatment;
import io.github.mikel0ne.l1copilot.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decide el tratamiento (tú/usted) con el que el asistente se dirige a cada usuario,
 * a partir de su edad y de un umbral configurable ({@code app.treatment.formal-age-threshold},
 * 60 por defecto). Punto único para que backend y, vía DTO, frontend coincidan.
 */
@Component
public class TreatmentPolicy {

    private final int formalAgeThreshold;

    public TreatmentPolicy(@Value("${app.treatment.formal-age-threshold:60}") int formalAgeThreshold) {
        this.formalAgeThreshold = formalAgeThreshold;
    }

    public Treatment treatmentFor(User user) {
        return Treatment.forAge(user.getAge(), formalAgeThreshold);
    }

    /** true si al usuario se le trata de usted (para que el frontend ajuste su tono). */
    public boolean isFormal(User user) {
        return treatmentFor(user) == Treatment.USTED;
    }
}
