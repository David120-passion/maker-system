package com.xinyue.maker.io;

import com.xinyue.maker.common.Exchange;

/**
 * 用于做市目标交易所（例如 Hyperliquid/Gate）的行情连接器，占位实现。
 */
public final class TargetExchangeMarketDataConnector implements MarketDataConnector {

    private final Exchange exchange;
    private final Normalizer normalizer;

    public TargetExchangeMarketDataConnector(Exchange exchange, Normalizer normalizer) {
        this.exchange = exchange;
        this.normalizer = normalizer;
    }

    @Override
    public Exchange exchange() {
        return exchange;
    }

    @Override
    public boolean referenceOnly() {
        return false;
    }

    @Override
    public void start() {
        // TODO 建立目标交易所行情连接，并调用 normalizer.onJsonMessage(exchange, payload)
    }

    @Override
    public void stop() {
        // TODO 关闭连接
    }
}

