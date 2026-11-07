package com.ispengya.sharding.integration.service;

import com.ispengya.sharding.integration.mapper.OrderMapper;
import com.ispengya.sharding.integration.model.TOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW 载体（阶段 4）：内层事务挂起外层、取新逻辑连接独立提交
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
}
