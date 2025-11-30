package com.xinyue.maker.io;

import com.xinyue.maker.common.Exchange;

/**
 * 抽象出的行情连接器，统一管理多交易所接入。
 */
public interface MarketDataConnector {

    Exchange exchange();

    /**
     * 该连接器是否只提供参考行情（策略侧使用），不会下单。
     */
    boolean referenceOnly();

    void start();

    void stop();
}

