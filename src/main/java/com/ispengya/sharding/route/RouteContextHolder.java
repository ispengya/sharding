package com.ispengya.sharding.route;

import com.ispengya.sharding.annotation.Route;

public class RouteContextHolder {
    private RouteContextHolder() {
    }

    private static final ThreadLocal<String> dbHolder = new ThreadLocal<>();
    private static final ThreadLocal<Route> routeHolder = new ThreadLocal<>();
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

    /**
     * 读写角色；未设置（null）按 MASTER 处理
     */
    public static void setRoute(Route route) {
        routeHolder.set(route);
    }

    public static Route getRoute() {
        return routeHolder.get();
    }

    public static void clearRoute() {
        routeHolder.remove();
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