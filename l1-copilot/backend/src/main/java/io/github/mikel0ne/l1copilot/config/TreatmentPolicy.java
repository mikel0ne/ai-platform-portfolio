package io.github.mikel0ne.l1copilot.config;

import io.github.mikel0ne.l1copilot.agent.Treatment;
import io.github.mikel0ne.l1copilot.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TreatmentPolicy {

    @Value("${app.treatment.formal-age-threshold:60}")
    private final int formalAgeThreshold;

    public Treatment treatmentFor(User user) {
        return Treatment.forAge(user.getAge(), formalAgeThreshold);
    }

    public boolean isFormal(User user) {
        return treatmentFor(user) == Treatment.USTED;
    }
}
