package com.xinyue.maker.strategy;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.core.lob.OrderBookSnapshot;

public final class StrategyEngine {

    private final SignalGenerator signalGenerator;
    private final ExecutionRouter executionRouter;
    private final RiskEngine riskEngine;
    private volatile boolean killSwitch;
    
    // 按 symbolId 路由的做市策略映射（支持多策略）
    private final java.util.concurrent.ConcurrentHashMap<Short, MarketMakingStrategy> strategiesBySymbolId = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 当前使用的做市策略（向后兼容，已废弃，保留用于兼容旧代码）
    @Deprecated
    private MarketMakingStrategy marketMakingStrategy;

    public StrategyEngine(SignalGenerator signalGenerator,
                          ExecutionRouter executionRouter,
                          RiskEngine riskEngine) {
        this.signalGenerator = signalGenerator;
        this.executionRouter = executionRouter;
        this.riskEngine = riskEngine;
    }
    
    /**
     * 设置做市策略（向后兼容方法，已废弃）。
     * 
     * @param strategy 做市策略实例
     * @deprecated 使用 {@link #setMarketMakingStrategy(short, MarketMakingStrategy)} 替代
     */
    @Deprecated
    public void setMarketMakingStrategy(MarketMakingStrategy strategy) {
        this.marketMakingStrategy = strategy;
    }
    
    /**
     * 获取当前做市策略（向后兼容方法，已废弃）。
     * 
     * @return 当前做市策略实例，可能为 null
     * @deprecated 使用 {@link #getMarketMakingStrategy(short)} 替代
     */
    @Deprecated
    public MarketMakingStrategy getMarketMakingStrategy() {
        return marketMakingStrategy;
    }
    
    /**
     * 设置指定 symbolId 的做市策略。
     * 
     * @param symbolId 交易对 ID
     * @param strategy 做市策略实例
     */
    public void setMarketMakingStrategy(short symbolId, MarketMakingStrategy strategy) {
        if (strategy == null) {
            strategiesBySymbolId.remove(symbolId);
        } else {
            strategiesBySymbolId.put(symbolId, strategy);
        }
    }
    
    /**
     * 获取指定 symbolId 的做市策略。
     * 
     * @param symbolId 交易对 ID
     * @return 做市策略实例，可能为 null
     */
    public MarketMakingStrategy getMarketMakingStrategy(short symbolId) {
        return strategiesBySymbolId.get(symbolId);
    }
    
    /**
     * 移除指定 symbolId 的做市策略。
     * 
     * @param symbolId 交易对 ID
     * @return 被移除的策略实例，如果不存在返回 null
     */
    public MarketMakingStrategy removeMarketMakingStrategy(short symbolId) {
        return strategiesBySymbolId.remove(symbolId);
    }
    
    /**
     * 获取所有已注册的策略 symbolId。
     * 
     * @return symbolId 集合
     */
    public java.util.Set<Short> getAllSymbolIds() {
        return new java.util.HashSet<>(strategiesBySymbolId.keySet());
    }

    public void onMarketData(CoreEvent event, OrderBookSnapshot snapshot) {
        if (killSwitch) {
            return;
        }
        SignalGenerator.SignalResult quote = signalGenerator.generate(snapshot);
        executionRouter.onQuote(event, quote);
    }

    public void onDepthUpdate(CoreEvent event) {
        if (killSwitch) {
            return;
        }
        
//        SignalGenerator.SignalResult quote = signalGenerator.generate(snapshot);
//        executionRouter.onQuote(event, quote);
    }
    
    /**
     * 处理深度更新事件（带参考订单簿快照）。
     */
    public void onDepthUpdate(CoreEvent event, OrderBookSnapshot referenceSnapshot) {
        if (killSwitch) {
            return;
        }
        
        // 按 symbolId 路由到对应的策略
        MarketMakingStrategy strategy = getStrategyForEvent(event);
        if (strategy != null) {
            strategy.onDepthUpdate(event, referenceSnapshot);
        }
    }
    
    /**
     * 处理账户订单更新事件。
     * 按 symbolId 路由到对应的策略。
     */
    public void onAccountOrderUpdate(CoreEvent event) {
        if (killSwitch) {
            return;
        }
        
        // 按 symbolId 路由到对应的策略
        MarketMakingStrategy strategy = getStrategyForEvent(event);
        if (strategy != null) {
            strategy.onAccountOrderUpdate(event);
        }
    }

    public void onExecution(CoreEvent event) {
        executionRouter.onExecution(event);
    }

    public void onCommand(CoreEvent event) {
        executionRouter.onCommand(event);
    }

    public void onConfig(CoreEvent event) {
        // TODO 解析配置更新载荷
    }

    public void onTimer(CoreEvent event) {
        if (killSwitch) {
            return;
        }
        
        riskEngine.onTimer(event);
        
        // 统一事件源：symbolId=0 表示全局事件，所有策略都收到
        // 策略内部自己判断是否需要下单（基于各自的间隔配置）
        if (event.symbolId == 0) {
            // 全局事件：调用所有策略
            for (MarketMakingStrategy strategy : strategiesBySymbolId.values()) {
                strategy.onTimer(event);
            }
        } else if (event.symbolId > 0) {
            // 特定 symbolId 的事件：路由到对应策略
            MarketMakingStrategy strategy = strategiesBySymbolId.get(event.symbolId);
            if (strategy != null) {
                strategy.onTimer(event);
            }
        }
    }
    
    /**
     * 根据事件获取对应的策略。
     * 优先使用 symbolId 路由，如果没有 symbolId 则返回 null。
     */
    private MarketMakingStrategy getStrategyForEvent(CoreEvent event) {
        if (event.symbolId > 0) {
            return strategiesBySymbolId.get(event.symbolId);
        }
        // 向后兼容：如果没有 symbolId，使用旧的单策略模式
        return marketMakingStrategy;
    }

    public void killSwitch() {
        killSwitch = true;
    }
}

