package io.github.mikel0ne.l1copilot.config;

import io.github.mikel0ne.l1copilot.agent.Treatment;
import io.github.mikel0ne.l1copilot.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TreatmentPolicy {

    private final int formalAgeThreshold;

    public TreatmentPolicy(@Value("${app.treatment.formal-age-threshold:60}") int formalAgeThreshold) {
        this.formalAgeThreshold = formalAgeThreshold;
    }

    public Treatment treatmentFor(User user) {
        return Treatment.forAge(user.getAge(), formalAgeThreshold);
    }

    public boolean isFormal(User user) {
        return treatmentFor(user) == Treatment.USTED;
    }
}
