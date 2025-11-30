package com.xinyue.maker.infra;

/**
 * 记录系统级 KPI，后续接入 Micrometer/Prometheus 等指标后端。
 */
public final class MetricsService {

    public void recordBookUpdate(short symbolId) {
    }

    public void recordExecution(short symbolId, long qtyE8) {
    }

    public void recordOrder(short symbolId) {
    }

    public void recordPositionUpdate(short accountId, long qtyE8) {
    }
}

