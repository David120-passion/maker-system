package com.xinyue.maker.core.lob;

import com.xinyue.maker.common.Exchange;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Object2ObjectHashMap;

/**
 * 管理多交易所、多币种的本地订单簿。
 * <p>
 * 结构：Exchange -> SymbolId -> LocalOrderBook
 * 使用 Agrona 集合实现零 GC 映射。
 */
public final class OrderBookManager {

    // Exchange -> SymbolId -> ILocalOrderBook
    private final Object2ObjectHashMap<Exchange, Int2ObjectHashMap<ILocalOrderBook>> orderBooks = new Object2ObjectHashMap<>();

    /**
     * 获取或创建指定交易所和币种的本地订单簿。
     * <p>
     * 默认使用 LocalOrderBookHashMap 实现（无序，快速插入/删除）。
     * 如需使用有序版本，请改用 {@link LocalOrderBookRBTree}。
     */
    public ILocalOrderBook getOrCreate(Exchange exchange, short symbolId) {
        Int2ObjectHashMap<ILocalOrderBook> exchangeBooks = orderBooks.computeIfAbsent(
                exchange,
                ignored -> new Int2ObjectHashMap<>()
        );
        return exchangeBooks.computeIfAbsent(
                symbolId,
                ignored -> new LocalOrderBookRBTree()
        );
    }

    /**
     * 获取指定交易所和币种的本地订单簿，如果不存在返回 null。
     */
    public ILocalOrderBook get(Exchange exchange, short symbolId) {
        Int2ObjectHashMap<ILocalOrderBook> exchangeBooks = orderBooks.get(exchange);
        if (exchangeBooks == null) {
            return null;
        }
        return exchangeBooks.get(symbolId);
    }

    /**
     * 移除指定交易所和币种的订单簿。
     */
    public void remove(Exchange exchange, short symbolId) {
        Int2ObjectHashMap<ILocalOrderBook> exchangeBooks = orderBooks.get(exchange);
        if (exchangeBooks != null) {
            exchangeBooks.remove(symbolId);
        }
    }
}

