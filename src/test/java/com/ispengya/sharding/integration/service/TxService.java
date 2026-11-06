package com.ispengya.sharding.integration.service;

import com.ispengya.sharding.integration.mapper.OrderMapper;
import com.ispengya.sharding.integration.model.TOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TxService {

    private final OrderMapper orderMapper;

    public TxService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
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
}
