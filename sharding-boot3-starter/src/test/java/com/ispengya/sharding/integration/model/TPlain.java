package com.ispengya.sharding.integration.model;

/**
 * 无分片规则的普通表实体：验证未配置 rules 的逻辑表直通 primary。
 */
public class TPlain {

    private String id;

    public TPlain() {
    }

    public TPlain(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
