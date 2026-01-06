package com.xinyue.maker.io.input;

import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.io.MarketDataConnector;
import com.xinyue.maker.io.input.dydx.DydxMarketDataConnector;

import java.util.ArrayList;
import java.util.List;

/**
 * 接入层协调器，负责统一启动/终止所有行情连接器，并提供部分控制能力（例如重新订阅）。
 */
public final class AccessLayerCoordinator {

    private final List<MarketDataConnector> connectors = new ArrayList<>();

    public AccessLayerCoordinator register(MarketDataConnector connector) {
        connectors.add(connector);
        return this;
    }

    public void startAll() {
        connectors.forEach(MarketDataConnector::start);
    }

    public void stopAll() {
        connectors.forEach(MarketDataConnector::stop);
    }

    /**
     * 由核心层调用，用于在检测到 dYdX gap 时执行「取消订阅 + 重新订阅」。
     */
    public void resubscribeOrderBook(Exchange exchange, String symbol) {
        for (MarketDataConnector connector : connectors) {
            if (connector.exchange() == exchange && connector instanceof DydxMarketDataConnector d) {
                d.resubscribeOrderBook(symbol);
            }
        }
    }

    /**
     * 根据交易所获取连接器。
     */
    public MarketDataConnector getConnector(Exchange exchange) {
        for (MarketDataConnector connector : connectors) {
            if (connector.exchange() == exchange) {
                return connector;
            }
        }
        return null;
    }
}

