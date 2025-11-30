package com.xinyue.maker.core;

import com.lmax.disruptor.EventHandler;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.CoreEventType;
import com.xinyue.maker.core.lob.LobManager;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.strategy.StrategyEngine;
import com.xinyue.maker.infra.PersistenceDispatcher;

/**
 * 表示 L2 整条单线程热路径的事件处理器。
 */
public final class CoreEventHandler implements EventHandler<CoreEvent> {

    private final LobManager lobManager;
    private final OrderManagementSystem oms;
    private final PositionManager positionManager;
    private final StrategyEngine strategyEngine;
    private final PersistenceDispatcher persistenceDispatcher;

    public CoreEventHandler(LobManager lobManager,
                            OrderManagementSystem oms,
                            PositionManager positionManager,
                            StrategyEngine strategyEngine,
                            PersistenceDispatcher persistenceDispatcher) {
        this.lobManager = lobManager;
        this.oms = oms;
        this.positionManager = positionManager;
        this.strategyEngine = strategyEngine;
        this.persistenceDispatcher = persistenceDispatcher;
    }

    @Override
    public void onEvent(CoreEvent event, long sequence, boolean endOfBatch) {
        try {
            switch (event.type) {
                case MARKET_DATA_TICK -> handleMarketData(event);
                case EXECUTION_REPORT -> handleExecution(event);
                case STRATEGY_COMMAND -> handleStrategyCommand(event);
                case CONFIG_UPDATE -> handleConfigUpdate(event);
                case TIMER -> strategyEngine.onTimer(event);
                default -> {
                }
            }
            persistenceDispatcher.publish(event);
        } catch (Throwable t) {
            strategyEngine.killSwitch();
        } finally {
            event.reset();
        }
    }

    private void handleMarketData(CoreEvent event) {
        lobManager.onMarketData(event);
        strategyEngine.onMarketData(event, lobManager.primaryReference());
    }

    private void handleExecution(CoreEvent event) {
        oms.onExecution(event);
        positionManager.onExecution(event);
        strategyEngine.onExecution(event);
    }

    private void handleStrategyCommand(CoreEvent event) {
        strategyEngine.onCommand(event);
    }

    private void handleConfigUpdate(CoreEvent event) {
        strategyEngine.onConfig(event);
    }
}

