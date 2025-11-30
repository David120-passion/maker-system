package com.xinyue.maker.core.position;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.infra.MetricsService;

/**
 * 每个账户维护一份基础资产 / 报价资产的双资产账本。
 */
public final class PositionManager {

    private final MetricsService metricsService;

    public PositionManager(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void reserve(short accountId, long baseQtyE8, long quoteQtyE8) {
        // TODO 使用原始类型 Map 管理可用/锁定仓位
    }

    public void onExecution(CoreEvent event) {
        metricsService.recordPositionUpdate(event.accountId, event.quantity);
    }
}

