package com.ispengya.sharding.integration.mapper;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.integration.model.TPlain;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 无规则表 Mapper：普通方法不标注（直通 primary），routed 方法验证键取不到时报错；
 * serverId/findIdOnSlave 系列用于读写分离验证（server_id：master-1=1、master-2=2、slave=3）。
 */
public interface PlainMapper {

    @Insert("INSERT INTO t_plain (id) VALUES (#{id})")
    int insertPlain(TPlain plain);

    @DBRouter
    @Select("SELECT id FROM t_plain WHERE id = #{id}")
    String routedByPlainWithoutShardingKey(TPlain plain);

    /** 默认 MASTER：按 userId 分片后仍读主库 */
    @DBRouter
    @Select("SELECT @@server_id")
    int defaultServerId(@Param("userId") String userId);

    /** SLAVE：按 userId 分片后读该组从库（无 slaves 的组降级 master） */
    @DBRouter(route = Route.SLAVE)
    @Select("SELECT @@server_id")
    int slaveServerId(@Param("userId") String userId);

    /** SLAVE：主库写入、复制后从库应能读到 */
    @DBRouter(route = Route.SLAVE)
    @Select("SELECT id FROM t_plain WHERE id = #{id}")
    String findIdOnSlave(@Param("id") String id, @Param("userId") String userId);
}
