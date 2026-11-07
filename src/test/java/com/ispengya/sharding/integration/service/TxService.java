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

    public TxService(OrderMapper orderMapper, PlainMapper plainMapper) {
        this.orderMapper = orderMapper;
        this.plainMapper = plainMapper;
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
}
