package com.xinyue.maker.common;

public enum CoreEventType {
    NONE,
    MARKET_DATA_TICK,
    DEPTH_UPDATE,        // 深度更新事件，用于本地订单簿维护
    EXECUTION_REPORT,
    ACCOUNT_ORDER_UPDATE, // 账户订单更新事件（dYdX v4_subaccounts）
    STRATEGY_COMMAND,
    CONFIG_UPDATE,
    TIMER,
    TEST                 // 测试事件，用于慢速订单测试策略
}

