package io.github.mikel0ne.autoops.rag.crag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("autoops.crag")
public record CragProperties(
        Integer k,
        Integer maxIterations,
        Double alpha,
        Integer kStep,
        Integer kMax,
        Integer multiQueryN,
        Integer retrievalParallelism
) {
    @ConstructorBinding
    public CragProperties {
        if (k == null) k = 5;
        if (maxIterations == null) maxIterations = 2;
        if (alpha == null) alpha = 0.5;
        if (kStep == null) kStep = 3;
        if (kMax == null) kMax = 12;
        if (multiQueryN == null) multiQueryN = 3;
        if (retrievalParallelism == null || retrievalParallelism < 1) retrievalParallelism = 4;
    }

    public CragProperties(Integer k, Integer maxIterations, Double alpha, Integer kStep, Integer kMax,
                          Integer multiQueryN) {
        this(k, maxIterations, alpha, kStep, kMax, multiQueryN, null);
    }
}
