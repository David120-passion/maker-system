package com.xinyue.maker.strategy;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;

public final class ExecutionRouter {

    private final OrderManagementSystem oms;
    private final PositionManager positionManager;

    public ExecutionRouter(OrderManagementSystem oms, PositionManager positionManager) {
        this.oms = oms;
        this.positionManager = positionManager;
    }

    public void onQuote(CoreEvent template, SignalGenerator.SignalResult quote) {
        // TODO 根据库存与风险规则评估当前报价
    }

    public void onExecution(CoreEvent event) {
        // TODO 更新子单追踪信息
    }

    public void onCommand(CoreEvent event) {
        // TODO 处理父单指令
    }
}

