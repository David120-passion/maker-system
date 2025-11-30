package com.xinyue.maker.core.oms;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.infra.MetricsService;
import com.xinyue.maker.infra.PersistenceDispatcher;

/**
 * 通过原始类型索引追踪订单全生命周期。
 */
public final class OrderManagementSystem {

    private final MetricsService metricsService;
    private final PersistenceDispatcher persistenceDispatcher;

    public OrderManagementSystem(MetricsService metricsService,
                                 PersistenceDispatcher persistenceDispatcher) {
        this.metricsService = metricsService;
        this.persistenceDispatcher = persistenceDispatcher;
    }

    public void onExecution(CoreEvent event) {
        // TODO 将成交回报映射到本地订单状态机
        metricsService.recordExecution(event.symbolId, event.quantity);
        persistenceDispatcher.publish(event);
    }

    public void submitOrder(CoreEvent command) {
        // TODO 从对象池中取出订单并异步交给 L1 执行
        metricsService.recordOrder(command.symbolId);
    }
}

