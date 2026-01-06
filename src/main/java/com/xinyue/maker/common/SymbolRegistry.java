package com.xinyue.maker.common;

import org.agrona.collections.Object2IntHashMap;

/**
 * Symbol 字符串到 symbolId 的映射注册表。
 * 使用 Agrona 的 Object2IntHashMap 实现零GC映射。
 */
public final class SymbolRegistry {

    private static final SymbolRegistry INSTANCE = new SymbolRegistry();
    private final Object2IntHashMap<String> symbolToIdMap = new Object2IntHashMap<>(-1);
    private final String[] idToSymbolMap = new String[Short.MAX_VALUE];
    private short nextId = 1;
    //todo  这里以后预注册交易对 都需要添加个 资产映射关系  如btcusdc  要根据assetRegistry提前注册的资产id映射 比如 1-2
    private SymbolRegistry() {
        // 预注册常见交易对
        register("BTCUSDT", (short) 1);
        register("ETHUSDT", (short) 2);
        register("BNBUSDT", (short) 3);
        register("H2USDT", (short) 5);
    }

    public static SymbolRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 注册或获取 symbol 对应的 symbolId。
     * 如果 symbol 不存在，自动分配新的 ID。
     */
    public short get(String symbol) {
        int id = symbolToIdMap.getValue(symbol);

        return (short) id;
    }

    private void register(String symbol, short id) {
        symbolToIdMap.put(symbol, id);
        idToSymbolMap[id] = symbol;
    }

    public String getSymbol(short symbolId) {
        if (symbolId <= 0 || symbolId >= idToSymbolMap.length) {
            return null;
        }
        return idToSymbolMap[symbolId];
    }
}

