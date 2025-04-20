package com.ispengya.sharding.sharding;

public class ShardingContextHolder {
    private ShardingContextHolder() {
    }

    private static final ThreadLocal<String> dbHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> tbHolder = new ThreadLocal<>();

    public static void setDbHolder(String dataSource) {
        dbHolder.set(dataSource);
    }

    public static String getDbHolder() {
        return dbHolder.get();
    }

    public static void clearDbHolder() {
        dbHolder.remove();
    }

    public static void setTbHolder(String dataSource) {
        tbHolder.set(dataSource);
    }

    public static String getTbHolder() {
        return tbHolder.get();
    }

    public static void clearTbHolder() {
        tbHolder.remove();
    }
}