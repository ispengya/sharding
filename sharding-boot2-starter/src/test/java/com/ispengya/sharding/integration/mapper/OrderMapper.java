package com.ispengya.sharding.integration.mapper;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.integration.model.TOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

public interface OrderMapper {

    @DBRouter
    @Insert("INSERT INTO t_order (id, user_id, order_id) VALUES (#{id}, #{userId}, #{orderId})")
    int insert(TOrder order);

    /**
     * 未标注解：语句级分库路由——SQL 碰 t_order 即按表规则取键路由，
     * 不再依赖注解（修掉"漏标注解静默落 primary"的错路由）。
     */
    @Insert("INSERT INTO t_order (id, user_id, order_id) VALUES (#{id}, #{userId}, #{orderId})")
    int insertAutoRouted(TOrder order);

    @DBRouter
    @Select("SELECT id FROM t_order WHERE id = #{id} AND order_id = #{orderId}")
    String findId(TOrder order);
}
