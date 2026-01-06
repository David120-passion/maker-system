package com.xinyue.maker.strategy;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.core.lob.OrderBookSnapshot;

/**
 * 做市策略接口。
 * 所有做市策略都需要实现此接口。
 */
public interface MarketMakingStrategy {
    
    /**
     * 在订单簿深度更新时调用。
     * 
     * @param event 深度更新事件
     * @param referenceSnapshot 参考订单簿快照（通常是 Binance）
     */
    void onDepthUpdate(CoreEvent event, OrderBookSnapshot referenceSnapshot);
    
    /**
     * 在账户订单更新时调用（用于处理撤单确认等异步操作）。
     * 
     * @param event 账户订单更新事件
     */
    void onAccountOrderUpdate(CoreEvent event);
    
    /**
     * 定时器事件（可选实现，用于周期性检查）。
     * 
     * @param event 定时器事件
     */
    default void onTimer(CoreEvent event) {
        // 默认空实现，策略可以选择性实现
    }
}

