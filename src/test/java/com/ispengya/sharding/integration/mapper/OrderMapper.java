package com.ispengya.sharding.integration.mapper;

import com.ispengya.sharding.DBRouter;
import com.ispengya.sharding.integration.model.TOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

public interface OrderMapper {

    @DBRouter
    @Insert("INSERT INTO t_order (id, user_id, order_id) VALUES (#{id}, #{userId}, #{orderId})")
    int insert(TOrder order);

    @DBRouter
    @Select("SELECT id FROM t_order WHERE id = #{id} AND order_id = #{orderId}")
    String findId(TOrder order);
}
