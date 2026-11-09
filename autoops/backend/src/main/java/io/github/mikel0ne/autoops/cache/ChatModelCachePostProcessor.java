package io.github.mikel0ne.autoops.cache;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.CacheProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class ChatModelCachePostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    // Dependencies are looked up lazily: BeanPostProcessors are created before regular beans.
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
