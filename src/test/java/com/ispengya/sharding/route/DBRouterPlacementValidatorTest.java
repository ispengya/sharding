package com.ispengya.sharding.route;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.exception.ShardingException;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertSame;

/**
 * @DBRouter 标注位置校验：接口（Mapper）允许，普通类启动即报错；JDK 代理按其接口判定。
 */
public class DBRouterPlacementValidatorTest {

    private final DBRouterPlacementValidator validator = new DBRouterPlacementValidator();

    public interface AnnotatedMapper {
        @DBRouter
        void load(String id);
    }

    @DBRouter
    public interface ClassAnnotatedMapper {
        void load(String id);
    }

    public static class AnnotatedService {
        @DBRouter
        public void load(String id) {
        }
    }

    @DBRouter
    public static class ClassAnnotatedService {
        public void load(String id) {
        }
    }

    public static class PlainBean {
        public void work() {
        }
    }

    @Test
    public void mapperInterfaceAllowed() {
        validator.validate("orderMapper", AnnotatedMapper.class);
        validator.validate("userMapper", ClassAnnotatedMapper.class);
    }

    @Test(expected = ShardingException.class)
    public void annotatedMethodOnConcreteClassThrows() {
        validator.validate("orderService", AnnotatedService.class);
    }

    @Test(expected = ShardingException.class)
    public void annotatedClassConcreteThrows() {
        validator.validate("payService", ClassAnnotatedService.class);
    }

    @Test
    public void plainBeanIgnored() {
        validator.validate("plainBean", PlainBean.class);
    }

    @Test
    public void jdkProxyOfAnnotatedInterfaceAllowed() {
        Object proxy = Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{AnnotatedMapper.class},
                (p, m, a) -> null);
        // JDK 代理类名形如 $ProxyN，按接口判定放行
        validator.validate("orderMapper", proxy.getClass());
        assertSame(AnnotatedMapper.class, proxy.getClass().getInterfaces()[0]);
    }
}
