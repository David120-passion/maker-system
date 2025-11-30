package com.xinyue.maker.io;

import java.util.ArrayList;
import java.util.List;

/**
 * 接入层协调器，负责统一启动/终止所有行情连接器。
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
}

