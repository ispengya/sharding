package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.annotation.TxMode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @TxMode 切面单测：方法级 > 类级、活动事务内生效、内层不覆盖入口模式、
 * XA/THREE_PC 生效时报"尚未实现"、未开事务不生效也不报错。
 */
public class TxModeAspectTest {

    @TxMode(Mode.LOCAL)
    public static class LocalAnnotated {
        public void run() {
        }
    }

    @TxMode(Mode.XA)
    public static class XaAnnotated {
        public void run() {
        }
    }

    @TxMode(Mode.LOCAL)
    public static class ClassLocalWithMethodXa {
        @TxMode(Mode.XA)
        public void run() {
        }
    }

    public interface Fixture {
        @TxMode(Mode.LOCAL)
        void localMethod();

        @TxMode(Mode.XA)
        void xaMethod();
    }

    private TxModeAspect aspect;

    @Before
    public void setUp() {
        aspect = new TxModeAspect();
        TxContextHolder.clearMode();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @After
    public void tearDown() {
        TxContextHolder.clearMode();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    // ---------- 模式解析（方法级 > 类级） ----------

    @Test
    public void methodLevelWinsOverClassLevel() throws Exception {
        Method method = ClassLocalWithMethodXa.class.getMethod("run");
        assertEquals(Mode.XA, aspect.resolveMode(method, ClassLocalWithMethodXa.class));
    }

    @Test
    public void classLevelAppliesWhenMethodBare() throws Exception {
        Method method = LocalAnnotated.class.getMethod("run");
        assertEquals(Mode.LOCAL, aspect.resolveMode(method, LocalAnnotated.class));
    }

    // ---------- 生效与还原 ----------

    @Test
    public void modeAppliedInActiveTransactionAndClearedAfter() throws Throwable {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Method method = Fixture.class.getMethod("localMethod");
        ProceedingJoinPoint jp = mockJoinPoint(method, Fixture.class);

        assertEquals("ok", aspect.doApplyMode(jp));
        // 帧结束还原为 null（入口帧设置、finally 清理）
        assertNull(TxContextHolder.getMode());
    }

    @Test
    public void modeVisibleDuringProceed() throws Throwable {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Method method = Fixture.class.getMethod("localMethod");
        ProceedingJoinPoint jp = mockJoinPoint(method, Fixture.class);
        when(jp.proceed()).thenAnswer(invocation -> {
            assertEquals(Mode.LOCAL, TxContextHolder.getMode());
            return "ok";
        });

        aspect.doApplyMode(jp);
    }

    @Test
    public void innerFrameKeepsEntranceMode() throws Throwable {
        // 入口已设 LOCAL：内层再标（此处为 XA 解析但被 previous 拦截，不抛不覆盖）
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TxContextHolder.setMode(Mode.LOCAL);
        Method method = Fixture.class.getMethod("xaMethod");
        ProceedingJoinPoint jp = mockJoinPoint(method, Fixture.class);
        when(jp.proceed()).thenAnswer(invocation -> {
            assertEquals("内层不得覆盖入口模式", Mode.LOCAL, TxContextHolder.getMode());
            return "ok";
        });

        assertEquals("ok", aspect.doApplyMode(jp));
        assertEquals(Mode.LOCAL, TxContextHolder.getMode());
    }

    @Test
    public void xaRecordedInFrameAndClearedAfterWithoutError() throws Throwable {
        // 切面只记录模式；"尚未实现"由提交时策略查找报出（见 ShardingConnectionTest.getCommitStrategy）
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Method method = Fixture.class.getMethod("xaMethod");
        ProceedingJoinPoint jp = mockJoinPoint(method, Fixture.class);
        when(jp.proceed()).thenAnswer(invocation -> {
            assertEquals(Mode.XA, TxContextHolder.getMode());
            return "ok";
        });

        assertEquals("ok", aspect.doApplyMode(jp));
        assertNull(TxContextHolder.getMode());
    }

    @Test
    public void noTransactionMeansNoEffectNoError() throws Throwable {
        // 未开事务：模式帧内短暂记录、无人消费、帧结束清理 → 不生效也不报错（XA 也不报）
        Method method = Fixture.class.getMethod("xaMethod");
        ProceedingJoinPoint jp = mockJoinPoint(method, Fixture.class);
        assertEquals("ok", aspect.doApplyMode(jp));
        assertNull(TxContextHolder.getMode());
    }

    // ---------- helpers ----------

    private ProceedingJoinPoint mockJoinPoint(Method method, Class<?> targetClass) throws Throwable {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(jp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(jp.getTarget()).thenReturn(targetClass);
        when(jp.getArgs()).thenReturn(new Object[0]);
        when(jp.proceed()).thenReturn("ok");
        return jp;
    }
}
