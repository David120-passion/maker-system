package com.xinyue.maker.core.lob;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.core.TestEventScheduler;
import com.xinyue.maker.infra.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 维护参考盘口（Binance）与目标盘口（交易所侧）的双盘面。
 * <p>
 * 本地订单簿维护（快照 + 增量）由 {@link LocalOrderBook} 完成，
 * 本类负责将 LocalOrderBook 的 bestBid/bestAsk 同步到对外暴露的 {@link OrderBookSnapshot} 上，
 * 供策略层读取。
 */
public final class LobManager {

    private static final Logger LOG = LoggerFactory.getLogger(LobManager.class);

    private final MetricsService metricsService;
    private final OrderBookRegistry registry = new OrderBookRegistry();
    private final OrderBookManager orderBookManager = new OrderBookManager();

    public LobManager(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * 目前仍作为占位：执行基本的指标记录。
     * 盘口本身由 LocalOrderBook 维护更新。
     */
    public void onMarketData(CoreEvent event) {
        metricsService.recordBookUpdate(event.symbolId);
    }

    /**
     * 将 ILocalOrderBook 的 bestBid/bestAsk 和深度同步到指定交易所和币种的参考盘口快照上。
     */
    public void syncFromLocalOrderBook(Exchange exchange, short symbolId, ILocalOrderBook orderBook) {

        OrderBookSnapshot snapshot = registry.referenceBook(exchange);
        snapshot.bestBidE8(orderBook.bestBidE8());
        snapshot.bestAskE8(orderBook.bestAskE8());
        snapshot.bestBidQtyE8(orderBook.bestBidQtyE8());
        snapshot.bestAskQtyE8(orderBook.bestAskQtyE8());

    }

    /**
     * 获取指定交易所和币种的本地订单簿。
     */
    public ILocalOrderBook getOrderBook(Exchange exchange, short symbolId) {
        return orderBookManager.getOrCreate(exchange, symbolId);
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

    /**
     * 清理指定 symbolId 的所有交易所订单簿。
     * 用于策略停止时清理相关订单簿数据。
     * 
     * @param symbolId 交易对ID
     */
    public void clearOrderBooksBySymbolId(short symbolId) {
        // 清理所有交易所的订单簿
        for (Exchange exchange : Exchange.values()) {
            orderBookManager.remove(exchange, symbolId);
        }
        LOG.info("已清理 symbolId={} 的所有交易所订单簿", symbolId);
    }
}

