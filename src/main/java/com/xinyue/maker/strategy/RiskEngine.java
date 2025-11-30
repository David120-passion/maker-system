package com.xinyue.maker.strategy;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.infra.MetricsService;

public final class RiskEngine {

    private final PositionManager positionManager;
    private final MetricsService metricsService;

    public RiskEngine(PositionManager positionManager, MetricsService metricsService) {
        this.positionManager = positionManager;
        this.metricsService = metricsService;
    }

    public boolean checkFatFinger(long priceE8, long quantityE8) {
        return true;
    }

    public void onTimer(CoreEvent event) {
        // TODO 周期性检查整体敞口
    }
}

