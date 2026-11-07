package com.ispengya.sharding.integration.mapper;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.integration.model.TPlain;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

/**
 * 无规则表 Mapper：普通方法不标注（直通 primary），routed 方法验证键取不到时报错。
 */
public interface PlainMapper {

    @Insert("INSERT INTO t_plain (id) VALUES (#{id})")
    int insertPlain(TPlain plain);

    @DBRouter
    @Select("SELECT id FROM t_plain WHERE id = #{id}")
    String routedByPlainWithoutShardingKey(TPlain plain);
}
