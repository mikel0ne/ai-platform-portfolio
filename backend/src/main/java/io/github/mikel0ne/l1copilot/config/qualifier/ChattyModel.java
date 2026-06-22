package io.github.mikel0ne.l1copilot.config.qualifier;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Modelo de charla informal (modo chatty, temperatura alta): calidez y variedad.
 * Lo consume el redactor {@code chattyResponder}.
 * <p>
 * Se llama {@code ChattyModel} y no {@code ChatModel} para no colisionar con el
 * tipo {@link dev.langchain4j.model.chat.ChatModel} en las clases de configuración.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE})
public @interface ChattyModel {
}
