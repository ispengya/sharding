package com.ispengya.sharding.tx.coordinator;

import com.ispengya.sharding.datasource.ShardingDataSource;
import com.ispengya.sharding.exception.ShardingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.SQLException;
import java.util.List;

/**
 * 启动恢复：上下文就绪、对外服务前同步扫描事务日志，兑现/清理悬决事务。
 * 规则：PREPARED → presumed commit（补 XA COMMIT）；BEGIN → presumed abort（补 ROLLBACK）；
 * 参与者 XA 名册中已无该分支（已被处理）→ 幂等跳过。
 * 日志表不存在（从未使用 XA）→ 静默跳过，零负担。
 */
@Slf4j
public class TransactionRecoveryListener implements ApplicationListener<ContextRefreshedEvent> {

    private final TransactionLog transactionLog;
    private final ShardingDataSource shardingDataSource;

    public TransactionRecoveryListener(TransactionLog transactionLog, ShardingDataSource shardingDataSource) {
        this.transactionLog = transactionLog;
        this.shardingDataSource = shardingDataSource;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        recoverPending();
    }

    /**
     * 扫描并解决全部悬决事务（包级可见，便于单测/集成测试直接驱动）
     */
    void recoverPending() {
        if (!transactionLog.existsQuietly()) {
            return;
        }
        resolveAll(transactionLog.findByState(TransactionLog.STATE_PREPARED), true);
        resolveAll(transactionLog.findByState(TransactionLog.STATE_BEGIN), false);
    }

    private void resolveAll(List<TransactionLog.LogEntry> entries, boolean presumedCommit) {
        for (TransactionLog.LogEntry entry : entries) {
            resolve(entry, presumedCommit);
        }
    }

    private void resolve(TransactionLog.LogEntry entry, boolean presumedCommit) {
        String globalId = entry.getGlobalId();
        boolean allResolved = true;
        StringBuilder detail = new StringBuilder(presumedCommit ? "recovered:commit" : "recovered:rollback");
        for (String nodeKey : entry.participantList()) {
            try {
                boolean resolved = resolveBranch(globalId, nodeKey, presumedCommit);
                if (!resolved) {
                    allResolved = false;
                    detail.append("; 分支已终态或无法定位:").append(nodeKey);
                }
            } catch (Exception e) {
                // 参与者不可达/裁决失败：保持日志非终态，下次启动重试
                allResolved = false;
                detail.append("; 失败:").append(nodeKey).append("(").append(e.getMessage()).append(")");
                log.error("2PC 恢复分支失败：globalId={} node={}", globalId, nodeKey, e);
            }
        }
        if (allResolved) {
            transactionLog.updateState(globalId,
                    presumedCommit ? TransactionLog.STATE_COMMITTED : TransactionLog.STATE_ROLLED_BACK,
                    detail.toString());
            log.info("2PC 恢复完成：globalId={} → {}", globalId,
                    presumedCommit ? TransactionLog.STATE_COMMITTED : TransactionLog.STATE_ROLLED_BACK);
        } else {
            // 保留原状态（PREPARED/BEGIN），下次启动继续
            transactionLog.updateState(globalId,
                    presumedCommit ? TransactionLog.STATE_PREPARED : TransactionLog.STATE_BEGIN,
                    detail.toString());
            log.warn("2PC 恢复未完全解决（保留待重试）：globalId={}，{}", globalId, detail);
        }
    }

    /**
     * 对单个参与节点执行恢复裁决
     *
     * @return true = 分支已解决或本就不在名册（幂等）；false = 无法定位（如节点配置已移除）
     */
    private boolean resolveBranch(String globalId, String nodeKey, boolean presumedCommit) throws SQLException {
        javax.sql.DataSource pool = shardingDataSource.getPool(nodeKey);
        if (pool == null) {
            log.error("2PC 恢复：参与者节点 {} 已不在当前配置中，无法裁决 globalId={}", nodeKey, globalId);
            return false;
        }
        if (!(pool instanceof XADataSource)) {
            throw new ShardingException("节点 " + nodeKey + " 的连接池不支持 XA，无法恢复");
        }
        XAConnection xaConnection = ((XADataSource) pool).getXAConnection();
        try {
            XAResource resource = xaConnection.getXAResource();
            Xid target = findBranch(resource, globalId, nodeKey);
            if (target == null) {
                // 名册中已无此分支：此前已被提交/回滚（或从未 prepare 就随会话回滚）→ 幂等
                return true;
            }
            if (presumedCommit) {
                resource.commit(target, false);
            } else {
                resource.rollback(target);
            }
            return true;
        } catch (XAException e) {
            throw new ShardingException("恢复裁决失败：globalId=" + globalId + " node=" + nodeKey, e);
        } finally {
            try {
                xaConnection.close();
            } catch (SQLException ignore) {
                // 归还失败不影响裁决结果
            }
        }
    }

    private Xid findBranch(XAResource resource, String globalId, String nodeKey) throws XAException {
        // Connector/J 对 TMNOFLAGS 返回空列表，必须带扫描标志（探针实测）
        Xid[] inDoubt = resource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
        if (inDoubt == null) {
            return null;
        }
        for (Xid candidate : inDoubt) {
            if (XidSupport.matches(candidate, globalId, nodeKey)) {
                return candidate;
            }
        }
        return null;
    }
}
