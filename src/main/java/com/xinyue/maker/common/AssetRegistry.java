package com.xinyue.maker.common;

import org.agrona.collections.Object2IntHashMap;

/**
 * 用于管理单个资产（如 "USDT", "BTC", "ORCL"）
 * 使用 Agrona 的 Object2IntHashMap 实现零GC映射。
 */
public final class AssetRegistry {

    private static final AssetRegistry INSTANCE = new AssetRegistry();
    private final Object2IntHashMap<String> assetToIdMap = new Object2IntHashMap<>(-1);
    private final String[] idToAssetMap = new String[Short.MAX_VALUE];
    private short nextId = 1;

    private AssetRegistry() {
        register("USDT",(short) 1);
        register("ORCL",(short) 2);
        register("BTC",(short) 3);
        register("ETH",(short) 4);
        register("H2",(short) 5);
    }

    public static AssetRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 注册或获取资产符号对应的 assetId。
     * 如果资产符号不存在，自动分配新的 ID。
     *
     * @param assetSymbol 资产符号（如 "USDT", "ORCL"）
     * @return assetId
     */
    public short get(String assetSymbol) {
        int id = assetToIdMap.getValue(assetSymbol);
        return (short) id;
    }

    private void register(String assetSymbol, short id) {
        assetToIdMap.put(assetSymbol, id);
        idToAssetMap[id] = assetSymbol;
    }

    /**
     * 根据 assetId 获取资产符号。
     *
     * @param assetId 资产 ID
     * @return 资产符号，如果不存在返回 null
     */
    public String getAsset(short assetId) {
        if (assetId <= 0 || assetId >= idToAssetMap.length) {
            return null;
        }
        return idToAssetMap[assetId];
    }
}

