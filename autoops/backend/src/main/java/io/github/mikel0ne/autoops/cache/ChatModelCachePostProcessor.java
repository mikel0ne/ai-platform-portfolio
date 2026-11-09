package io.github.mikel0ne.autoops.cache;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.CacheProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Envuelve transparentemente el bean {@link ChatModel} con {@link CachingChatModel} cuando la caché está
 * activada ({@code cache.enabled=true}). Así el RAG y el chat usan el modelo cacheado sin saberlo y sin
 * tocar el código del proveedor (doc 06: la abstracción no cambia).
 *
 * <p>Las dependencias se resuelven de forma perezosa desde el {@link BeanFactory} para no forzar la
 * creación temprana de beans (los {@code BeanPostProcessor} se instancian muy pronto en el ciclo de vida).
 */
@Component
public class ChatModelCachePostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ChatModel chatModel && !(bean instanceof CachingChatModel)) {
            CacheProperties props = beanFactory.getBean(CacheProperties.class);
            if (props.isCacheEnabled()) {
                return new CachingChatModel(chatModel, beanFactory.getBean(FileCacheManager.class));
            }
        }
        return bean;
    }
}
