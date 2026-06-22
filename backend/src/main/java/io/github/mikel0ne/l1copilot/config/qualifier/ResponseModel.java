package io.github.mikel0ne.l1copilot.config.qualifier;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Modelo de respuestas de negocio (temperatura media): tono natural, fiel a los
 * datos. Lo consume el redactor principal ({@code Responder}).
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE})
public @interface ResponseModel {
}
