package com.xinyue.maker.core.lob;

import com.xinyue.maker.common.Exchange;

import java.util.EnumMap;
import java.util.Map;

/**
 * 维护多交易所的参考盘口与目标盘口。
 */
public final class OrderBookRegistry {

    private final Map<Exchange, OrderBookSnapshot> referenceBooks = new EnumMap<>(Exchange.class);
    private final Map<Exchange, OrderBookSnapshot> battleBooks = new EnumMap<>(Exchange.class);

    public OrderBookSnapshot referenceBook(Exchange exchange) {
        return referenceBooks.computeIfAbsent(exchange, ignored -> new OrderBookSnapshot());
    }

    public OrderBookSnapshot battleBook(Exchange exchange) {
        return battleBooks.computeIfAbsent(exchange, ignored -> new OrderBookSnapshot());
    }
}

