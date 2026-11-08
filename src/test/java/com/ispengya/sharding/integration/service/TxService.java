package com.ispengya.sharding.integration.service;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.annotation.TxMode;
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
     * 活动事务内调用 route = SLAVE → 切面校验应抛 ShardingException
     */
    @Transactional
    public int readSlaveInTransaction(String userId) {
        return plainMapper.slaveServerId(userId);
    }

    /**
     * 跨分片写 + @TxMode(LOCAL)：循环提交，两行都落库
     */
    @TxMode(Mode.LOCAL)
    @Transactional
    public void insertTwoShards(TOrder first, TOrder second) {
        orderMapper.insert(first);
        orderMapper.insert(second);
    }

    /**
     * 跨分片写 + @TxMode(LOCAL) 后回滚：循环回滚，两行都撤销
     */
    @TxMode(Mode.LOCAL)
    @Transactional
    public void insertTwoShardsThenBoom(TOrder first, TOrder second) {
        orderMapper.insert(first);
        orderMapper.insert(second);
        throw new IllegalStateException("boom");
    }

    /**
     * 跨分片写但未标 @TxMode：写入第二个数据源时调用即报错，事务整体回滚
     */
    @Transactional
    public void insertTwoShardsWithoutTxMode(TOrder first, TOrder second) {
        orderMapper.insert(first);
        orderMapper.insert(second);
    }

    /**
     * 跨数据源只读事务：不进写名单，无需 @TxMode
     */
    @Transactional
    public void readAcrossShards(String ds0User, String ds1User, String orderId) {
        orderMapper.findId(new TOrder(null, ds0User, orderId));
        orderMapper.findId(new TOrder(null, ds1User, orderId));
    }

    /**
     * 外层跨分片写（已标 LOCAL）后再开 REQUIRES_NEW 内层写 → 内层写入即报错
     */
    @TxMode(Mode.LOCAL)
    @Transactional
    public void insertTwoShardsThenInnerRequiresNew(TOrder first, TOrder second, TOrder inner) {
        orderMapper.insert(first);
        orderMapper.insert(second);
        requiringNewService.insertOk(inner);
    }

    /**
     * 外层单分片写（已标 LOCAL）+ NESTED 内层写第二个数据源 → 内层写入即报错
     */
    @TxMode(Mode.LOCAL)
    @Transactional
    public void insertOneShardThenNestedSecondShard(TOrder first, TOrder nested) {
        orderMapper.insert(first);
        requiringNewService.insertNested(nested);
    }

    /**
     * 合法的 REQUIRES_NEW：外层与内层各自单分片写，互不影响（无需标注）
     */
    @Transactional
    public void insertOuterThenInnerRequiresNew(TOrder outer, TOrder inner) {
        orderMapper.insert(outer);
        requiringNewService.insertOk(inner);
        throw new IllegalStateException("boom");
    }

    /**
     * 跨分片写 + @TxMode(XA)：两阶段提交，两行同时可见、日志落 COMMITTED
     */
    @TxMode(Mode.XA)
    @Transactional
    public void insertTwoShardsXa(TOrder first, TOrder second) {
        orderMapper.insert(first);
        orderMapper.insert(second);
    }
}
