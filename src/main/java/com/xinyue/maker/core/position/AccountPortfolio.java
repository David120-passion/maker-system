package com.xinyue.maker.core.position;

import org.agrona.collections.Int2ObjectHashMap;

public class AccountPortfolio {
    public final int accountId;
    
    // 二级索引：AssetID -> Asset
    // 注意：这里使用的是资产ID（assetId，如 USDT、BTC），不是交易对符号ID（symbolId，如 BTCUSDT）
    // 例如：1=USDT, 2=BTC, 3=ETH
    private final Int2ObjectHashMap<Asset> assets = new Int2ObjectHashMap<>();
    
    // 费率配置 (不同账号可能不同)
    public long makerFeeBps; // 万分位
    public long takerFeeBps;

    public AccountPortfolio(int accountId) {
        this.accountId = accountId;
    }

    /**
     * 根据资产ID获取资产对象。
     * 如果没有，自动创建一个空的返回（避免空指针）。
     * 
     * @param assetId 资产ID（assetId，如 USDT、BTC），不是交易对符号ID
     * @return Asset 对象
     */
    public Asset getAsset(int assetId) {
        return assets.computeIfAbsent(assetId, (k) -> new Asset());
    }
}