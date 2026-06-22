package io.github.mikel0ne.l1copilot.config.qualifier;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Modelo determinista (temperatura 0.0): router y verificador. Es además el
 * {@link org.springframework.context.annotation.Primary}, así que las inyecciones
 * sin cualificar también lo reciben.
 * <p>
 * Qualifier tipado: un error de cableado se detecta al compilar/arrancar el
 * contexto, no en runtime como con {@code @Qualifier("string")}.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE})
public @interface Deterministic {
}
