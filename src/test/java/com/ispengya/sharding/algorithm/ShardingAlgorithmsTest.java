package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.testsupport.AlgorithmFixtures;
import com.ispengya.sharding.testsupport.AlgorithmFixtures.AlgorithmA;
import com.ispengya.sharding.testsupport.AlgorithmFixtures.AlgorithmB;
import com.ispengya.sharding.testsupport.AlgorithmFixtures.BareAlgorithm;
import com.ispengya.sharding.testsupport.AlgorithmFixtures.BlankNameAlgorithm;
import com.ispengya.sharding.testsupport.AlgorithmFixtures.DuplicateFirst;
import com.ispengya.sharding.testsupport.AlgorithmFixtures.DuplicateSecond;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 算法收集器测试：按 @Algorithm 注解名收敛、未标注/空名/重名启动期报错。
 */
public class ShardingAlgorithmsTest {

    @Test
    public void collectsByAnnotationValue() {
        AlgorithmA a = new AlgorithmA();
        Map<String, ShardingAlgorithm> map = ShardingAlgorithms.collect(Arrays.asList(a, new AlgorithmB()));
        assertTrue(map.containsKey("custom_a"));
        assertTrue(map.containsKey("custom_b"));
        assertSame(a, map.get("custom_a"));
    }

    @Test
    public void emptyCandidatesYieldEmptyMap() {
        assertTrue(ShardingAlgorithms.collect(Collections.<ShardingAlgorithm>emptyList()).isEmpty());
        assertTrue(ShardingAlgorithms.collect(null).isEmpty());
    }

    @Test(expected = ShardingException.class)
    public void missingAnnotationThrows() {
        ShardingAlgorithms.collect(Collections.<ShardingAlgorithm>singletonList(new BareAlgorithm()));
    }

    @Test(expected = ShardingException.class)
    public void blankNameThrows() {
        ShardingAlgorithms.collect(Collections.<ShardingAlgorithm>singletonList(new BlankNameAlgorithm()));
    }

    @Test(expected = ShardingException.class)
    public void duplicateNameThrows() {
        ShardingAlgorithms.collect(Arrays.asList(
                (ShardingAlgorithm) new DuplicateFirst(), new DuplicateSecond()));
    }
}
