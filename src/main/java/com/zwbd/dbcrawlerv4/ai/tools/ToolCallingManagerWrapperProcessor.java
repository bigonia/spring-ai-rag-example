package com.zwbd.dbcrawlerv4.ai.tools;

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * @Author: wnli
 * @Date: 2025/10/24 16:13
 * @Desc:
 */
@Component
public class ToolCallingManagerWrapperProcessor implements BeanPostProcessor {

    private static final String TARGET_BEAN_NAME = "originalBeanName";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (beanName.equals(TARGET_BEAN_NAME) && bean instanceof ToolCallingManager) {
            ToolCallingManager originalManager = (ToolCallingManager) bean;
            return new ToolCallingManagerWrap(originalManager);
        }
        return bean;
    }
}
