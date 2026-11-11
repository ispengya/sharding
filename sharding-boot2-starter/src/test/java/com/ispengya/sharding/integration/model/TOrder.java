package com.ispengya.sharding.integration.model;

/**
 * 测试实体：单 POJO 传参，分库键 userId / 分表键 orderId 都从该对象反射提取。
 */
public class TOrder {

    private String id;
    private String userId;
    private String orderId;

    public TOrder() {
    }

    public TOrder(String id, String userId, String orderId) {
        this.id = id;
        this.userId = userId;
        this.orderId = orderId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
