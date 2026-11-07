package com.ispengya.sharding.integration.service;

import com.ispengya.sharding.integration.mapper.OrderMapper;
import com.ispengya.sharding.integration.mapper.PlainMapper;
import com.ispengya.sharding.integration.model.TOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TxService {

    private final OrderMapper orderMapper;
    private final PlainMapper plainMapper;
    private final RequiringNewService requiringNewService;

    public TxService(OrderMapper orderMapper, PlainMapper plainMapper, RequiringNewService requiringNewService) {
        this.orderMapper = orderMapper;
        this.plainMapper = plainMapper;
        this.requiringNewService = requiringNewService;
    }

    @Transactional
    public void insertOk(TOrder order) {
        orderMapper.insert(order);
    }

    @Transactional
    public void insertAndBoom(TOrder order) {
        orderMapper.insert(order);
        throw new IllegalStateException("boom");
    }

    /**
     * 活动事务内调用 route = SLAVE → 切面校验应抛 ShardingException（阶段 3）
     */
    @Transactional
    public int readSlaveInTransaction(String userId) {
        return plainMapper.slaveServerId(userId);
    }

    /**
     * 一个事务写两个数据源（阶段 4）：两条记录分别按 userId 路由到 ds_0 / ds_1
     */
    @Transactional
    public void insertTwoShards(TOrder first, TOrder second) {
        orderMapper.insert(first);
        orderMapper.insert(second);
    }

    /**
     * 跨分片写后回滚（阶段 4）：循环回滚两个数据源
     */
    @Transactional
    public void insertTwoShardsThenBoom(TOrder first, TOrder second) {
        orderMapper.insert(first);
        orderMapper.insert(second);
        throw new IllegalStateException("boom");
    }

    /**
     * 外层事务写 ds_0 后内层 REQUIRES_NEW 写 ds_1 并提交，外层回滚（阶段 4）：
     * 内层独立逻辑连接不受外层回滚影响
     */
    @Transactional
    public void insertOuterThenInnerRequiresNew(TOrder outer, TOrder inner) {
        orderMapper.insert(outer);
        requiringNewService.insertOk(inner);
        throw new IllegalStateException("boom");
    }
}
