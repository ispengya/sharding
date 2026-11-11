package com.ispengya.sharding.datasource;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;

/**
 * XA 分支：逻辑连接在某物理节点上开启的 XA 事务分支。
 * 状态机：STARTED（XA START 后、SQL 执行期）→ ENDED（XA END 后）→ PREPARED → DONE（已提交/回滚）。
 */
public class XaBranch {

    public enum State {
        STARTED, ENDED, PREPARED, DONE
    }

    private final String nodeKey;
    private final XAResource resource;
    private final Xid xid;
    private final Connection connection;
    private State state = State.STARTED;

    public XaBranch(String nodeKey, XAResource resource, Xid xid, Connection connection) {
        this.nodeKey = nodeKey;
        this.resource = resource;
        this.xid = xid;
        this.connection = connection;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public XAResource getResource() {
        return resource;
    }

    public Xid getXid() {
        return xid;
    }

    public Connection getConnection() {
        return connection;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }
}
