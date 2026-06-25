package io.github.mikel0ne.l1copilot.config.qualifier;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redactor de charla informal (modo chatty): misma interfaz que el {@code Responder}
 * de negocio, pero conectado al {@link ChattyModel} de mayor temperatura.
 * <p>
 * Se llama {@code ChattyResponder} y no {@code Responder} porque ya existe el tipo
 * {@code io.github.mikel0ne.l1copilot.agent.llm.Responder} que cualifica.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE})
public @interface ChattyResponder {
}
