package com.ispengya.sharding.tx.coordinator;

import javax.transaction.xa.Xid;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Xid 生成：全局事务 id = 无连字符 UUID；分支限定符 = 物理节点 key（确定性，
 * 恢复时可由日志的 globalId + 参与者清单重建同样的 Xid 去匹配 in-doubt 名册）。
 */
public final class XidSupport {

    /**
     * formatId 固定为 "SHAR" 的 ASCII，标识本框架的 Xid 空间
     */
    public static final int FORMAT_ID = 0x53484152;

    private XidSupport() {
    }

    public static String newGlobalId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public static Xid create(String globalId, String branchQualifier) {
        return new SimpleXid(FORMAT_ID,
                globalId.getBytes(StandardCharsets.UTF_8),
                branchQualifier.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按日志中的 globalId + 节点 key 判断名册里的 Xid 是否本框架的分支
     */
    public static boolean matches(Xid candidate, String globalId, String branchQualifier) {
        return candidate != null
                && candidate.getFormatId() == FORMAT_ID
                && Arrays.equals(candidate.getGlobalTransactionId(), globalId.getBytes(StandardCharsets.UTF_8))
                && Arrays.equals(candidate.getBranchQualifier(), branchQualifier.getBytes(StandardCharsets.UTF_8));
    }

    private static final class SimpleXid implements Xid {
        private final int formatId;
        private final byte[] globalId;
        private final byte[] branchQualifier;

        private SimpleXid(int formatId, byte[] globalId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalId = globalId;
            this.branchQualifier = branchQualifier;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Xid)) {
                return false;
            }
            Xid other = (Xid) o;
            return formatId == other.getFormatId()
                    && Arrays.equals(globalId, other.getGlobalTransactionId())
                    && Arrays.equals(branchQualifier, other.getBranchQualifier());
        }

        @Override
        public int hashCode() {
            int result = formatId;
            result = 31 * result + Arrays.hashCode(globalId);
            result = 31 * result + Arrays.hashCode(branchQualifier);
            return result;
        }

        @Override
        public String toString() {
            return "Xid{" + new String(globalId, StandardCharsets.UTF_8)
                    + "," + new String(branchQualifier, StandardCharsets.UTF_8) + "," + formatId + "}";
        }
    }
}
