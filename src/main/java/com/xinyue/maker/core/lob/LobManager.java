package com.xinyue.maker.core.lob;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.infra.MetricsService;

/**
 * 维护参考盘口（Binance）与目标盘口（交易所侧）的双盘面。
 * 目前仅保留占位实现，等待后续接入行情处理器。
 */
public final class LobManager {

    private final MetricsService metricsService;
    private final OrderBookRegistry registry = new OrderBookRegistry();

    public LobManager(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void onMarketData(CoreEvent event) {
        Exchange exchange = Exchange.fromId(event.exchangeId);
        OrderBookSnapshot targetBook = exchange.referenceOnly()
                ? registry.referenceBook(exchange)
                : registry.battleBook(exchange);
        // TODO 将归一化后的行情写入订单簿数组
        metricsService.recordBookUpdate(event.symbolId);
    }

    public OrderBookSnapshot referenceSnapshot(Exchange exchange) {
        return registry.referenceBook(exchange);
    }

    public OrderBookSnapshot battleSnapshot(Exchange exchange) {
        return registry.battleBook(exchange);
    }

    public OrderBookSnapshot primaryReference() {
        return registry.referenceBook(Exchange.BINANCE);
    }
}

