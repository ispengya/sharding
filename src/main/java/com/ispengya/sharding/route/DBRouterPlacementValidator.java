package com.ispengya.sharding.route;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.exception.ShardingException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 启动期校验 @DBRouter 标注位置：只允许出现在接口（Mapper）上。
 * 标注到普通类（如 Service）会因切点匹配方式导致语义混乱，这里直接启动失败，防止静默不生效。
 */
public class DBRouterPlacementValidator implements ApplicationListener<ContextRefreshedEvent> {

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        ListableBeanFactory beanFactory = event.getApplicationContext();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = beanFactory.getType(beanName);
            } catch (Exception e) {
                continue;
            }
            if (type != null) {
                validate(beanName, type);
            }
        }
    }

    void validate(String beanName, Class<?> type) {
        if (!hasDBRouter(type)) {
            return;
        }
        if (type.isInterface()) {
            return;
        }
        // AOP 代理（JDK/CGLIB）：注解可能来自其接口或目标类
        if (isProxy(type)) {
            for (Class<?> itf : type.getInterfaces()) {
                if (hasDBRouter(itf)) {
                    return;
                }
            }
        }
        throw new ShardingException("@DBRouter 只能标注在 Mapper 接口上，当前标注在 Bean [" + beanName
                + "] 类型 " + type.getName() + " 上");
    }

    private boolean hasDBRouter(Class<?> type) {
        if (type.isAnnotationPresent(DBRouter.class)) {
            return true;
        }
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.isAnnotationPresent(DBRouter.class)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProxy(Class<?> type) {
        String name = type.getName();
        return name.contains("$$") || name.contains("$Proxy");
    }
}
