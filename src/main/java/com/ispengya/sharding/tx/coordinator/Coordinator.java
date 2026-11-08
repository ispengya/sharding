package com.ispengya.sharding.tx.coordinator;

import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.datasource.XaBranch;
import com.ispengya.sharding.exception.ShardingException;
import lombok.extern.slf4j.Slf4j;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2PC 协调者：编排 XA 分支的 prepare/commit/rollback 并推进事务日志。
 * 裁决规则：prepare 全部成功 → PREPARED（承诺点）→ 全部 commit → COMMITTED；
 * 任一 prepare 失败 → 全部 rollback → ROLLED_BACK；
 * commit 中途失败 → 日志保持 PREPARED，交给启动恢复兑现（与 LOCAL 的尽力语义不同）。
 * 分支无一开启（空事务）时各操作均为无日志的空操作。
 */
@Slf4j
public class Coordinator {

    private final TransactionLog transactionLog;

    public Coordinator(TransactionLog transactionLog) {
        this.transactionLog = transactionLog;
    }

    /**
     * 阶段一：登记 BEGIN → 逐分支 XA END + PREPARE → 全过写 PREPARED，任一失败全部回滚并抛出
     */
    public void prepare(ShardingConnection connection) throws SQLException {
        Map<String, XaBranch> branches = connection.getXaBranches();
        if (branches.isEmpty()) {
            return;
        }
        String globalId = connection.getXaGlobalId();
        transactionLog.record(globalId, TransactionLog.STATE_BEGIN,
                new ArrayList<>(branches.keySet()), null);

        XAException failure = null;
        String failedNode = null;
        for (XaBranch branch : branches.values()) {
            try {
                if (branch.getState() == XaBranch.State.STARTED) {
                    branch.getResource().end(branch.getXid(), XAResource.TMSUCCESS);
                    branch.setState(XaBranch.State.ENDED);
                }
                branch.getResource().prepare(branch.getXid());
                branch.setState(XaBranch.State.PREPARED);
            } catch (XAException e) {
                failure = e;
                failedNode = branch.getNodeKey();
                break;
            }
        }

        if (failure != null) {
            String detail = "prepare 失败节点=" + failedNode + "，全部回滚";
            log.error("2PC {}: globalId={}", detail, globalId, failure);
            rollbackBranches(branches);
            transactionLog.updateState(globalId, TransactionLog.STATE_ROLLED_BACK, detail);
            throw new ShardingException("2PC prepare 失败（" + detail + "）：globalId=" + globalId, failure);
        }

        transactionLog.updateState(globalId, TransactionLog.STATE_PREPARED, null);
        log.debug("2PC prepare 完成（全部通过）：globalId={}，分支={}", globalId, branches.keySet());
    }

    /**
     * 阶段二：逐分支 XA COMMIT → 全过写 COMMITTED；
     * 中途失败不改日志状态（保持 PREPARED），抛出交由启动恢复兑现
     */
    public void commit(ShardingConnection connection) throws SQLException {
        Map<String, XaBranch> branches = connection.getXaBranches();
        if (branches.isEmpty()) {
            return;
        }
        String globalId = connection.getXaGlobalId();
        List<String> failed = new ArrayList<>();
        XAException first = null;
        for (XaBranch branch : branches.values()) {
            if (branch.getState() == XaBranch.State.DONE) {
                continue;
            }
            try {
                branch.getResource().commit(branch.getXid(), false);
                branch.setState(XaBranch.State.DONE);
            } catch (XAException e) {
                failed.add(branch.getNodeKey());
                if (first == null) {
                    first = e;
                }
            }
        }

        if (!failed.isEmpty()) {
            // 关键语义：提交承诺已做出，日志停在 PREPARED，恢复时按 presumed commit 兑现
            String detail = "commit 未完成节点=" + failed + "，等待启动恢复兑现";
            transactionLog.updateState(globalId, TransactionLog.STATE_PREPARED, detail);
            log.error("2PC {}: globalId={}", detail, globalId, first);
            throw new ShardingException("2PC commit 未完成（" + detail + "）：globalId=" + globalId, first);
        }
        transactionLog.updateState(globalId, TransactionLog.STATE_COMMITTED, null);
        log.debug("2PC commit 完成：globalId={}，分支={}", globalId, branches.keySet());
    }

    /**
     * 回滚：按分支状态收尾（STARTED 先 XA END(TMFAIL)，其余直接 XA ROLLBACK），
     * 全部成功写 ROLLED_BACK；部分失败保持原状态交由恢复按 presumed abort 重试
     */
    public void rollback(ShardingConnection connection) throws SQLException {
        Map<String, XaBranch> branches = connection.getXaBranches();
        if (branches.isEmpty()) {
            return;
        }
        String globalId = connection.getXaGlobalId();
        List<String> failed = rollbackBranches(branches);
        if (!failed.isEmpty()) {
            String detail = "rollback 未完成节点=" + failed + "，等待启动恢复重试";
            log.error("2PC {}: globalId={}", detail, globalId);
            throw new ShardingException("2PC rollback 未完成（" + detail + "）：globalId=" + globalId);
        }
        // 未记录过日志的事务（prepare 前异常）updateState 为无行空操作
        transactionLog.updateState(globalId, TransactionLog.STATE_ROLLED_BACK, null);
        log.debug("2PC rollback 完成：globalId={}，分支={}", globalId, branches.keySet());
    }

    /**
     * @return 未能完成回滚的节点（空 = 全部完成）
     */
    private List<String> rollbackBranches(Map<String, XaBranch> branches) {
        List<String> failed = new ArrayList<>();
        for (XaBranch branch : branches.values()) {
            if (branch.getState() == XaBranch.State.DONE) {
                continue;
            }
            try {
                if (branch.getState() == XaBranch.State.STARTED) {
                    try {
                        branch.getResource().end(branch.getXid(), XAResource.TMFAIL);
                        branch.setState(XaBranch.State.ENDED);
                    } catch (XAException endFailure) {
                        // end 失败常见于分支已非 ACTIVE，继续尝试 rollback
                        log.debug("XA END(TMFAIL) 忽略（节点 {}）: {}", branch.getNodeKey(), endFailure.getMessage());
                    }
                }
                branch.getResource().rollback(branch.getXid());
                branch.setState(XaBranch.State.DONE);
            } catch (XAException e) {
                failed.add(branch.getNodeKey());
                log.error("XA ROLLBACK 失败（节点 {}）", branch.getNodeKey(), e);
            }
        }
        return failed;
    }
}
