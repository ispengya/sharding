package com.ispengya.sharding.integration.service;

import com.ispengya.sharding.integration.mapper.OrderMapper;
import com.ispengya.sharding.integration.model.TOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立事务传播载体：REQUIRES_NEW（挂起外层、新逻辑连接独立提交）与 NESTED（savepoint 内层）
 */
@Service
public class RequiringNewService {

    private final OrderMapper orderMapper;

    public RequiringNewService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertOk(TOrder order) {
        orderMapper.insert(order);
    }

    @Transactional(propagation = Propagation.NESTED)
    public void insertNested(TOrder order) {
        orderMapper.insert(order);
    }
}
