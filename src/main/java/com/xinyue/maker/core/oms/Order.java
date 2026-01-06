package com.xinyue.maker.core.oms;

/**
 * 订单对象（Zero GC，预分配复用）。
 * 用于在 L2 热路径中追踪订单全生命周期。
 */
public final class Order {
    
    // === 身份标识 ===
    public long localOrderId;      // 内部唯一 ID
    public long exchangeOrderId;    // 交易所返回的订单 ID（0 表示未收到）
    public short accountId;         // 账户 ID
    public short symbolId;          // 交易对 ID
    public short exchangeId;        // 交易所 ID
    
    // === 订单属性 ===
    public long priceE8;            // 价格（放大 1e8）
    public long qtyE8;              // 数量（放大 1e8）
    public long filledQtyE8;       // 已成交数量（放大 1e8）
    public byte side;               // 0=Buy, 1=Sell
    public byte orderType;          // 1=Limit, 2=Market, 3=PostOnly
    public byte orderStatus;        // 1=Created, 2=PendingNew, 3=New, 4=PartiallyFilled, 5=Filled, 6=Canceled, 7=Rejected

    // === dYdX v4 相关字段（撤单 / 追踪用）===
    public int clobPairId;          // clobPairId（例如 1000001）
    public long orderFlags;         // orderFlags（例如 64）
    public long goodTilBlockTimeSec; // goodTilBlockTime（epoch seconds）
    
    // === 时间戳 ===
    public long createTime;         // 订单创建时间（本地时间戳）
    public long submitTime;         // 提交到交易所时间
    public long updateTime;         // 最后更新时间
    
    /**
     * 重置订单对象（用于对象池复用）。
     */
    public void reset() {
        localOrderId = 0;
        exchangeOrderId = 0;
        accountId = 0;
        symbolId = 0;
        exchangeId = 0;
        priceE8 = 0;
        qtyE8 = 0;
        filledQtyE8 = 0;
        side = 0;
        orderType = 0;
        orderStatus = 0;
        clobPairId = 0;
        orderFlags = 0;
        goodTilBlockTimeSec = 0;
        createTime = 0;
        submitTime = 0;
        updateTime = 0;
    }
    
    /**
     * 检查订单是否处于活跃状态（可以成交或取消）。
     */
    public boolean isActive() {
        return orderStatus == 2 || orderStatus == 3 || orderStatus == 4; // PendingNew, New, PartiallyFilled
    }
    
    /**
     * 检查订单是否完全成交。
     */
    public boolean isFilled() {
        return orderStatus == 5; // Filled
    }
    
    /**
     * 获取剩余未成交数量。
     */
    public long getRemainingQtyE8() {
        return qtyE8 - filledQtyE8;
    }
}

