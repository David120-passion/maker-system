package com.xinyue.maker.common;


/**
 * L2 生成的原子指令 (POJO 或 预分配对象)
 */
public class OrderCommand {
    public long internalOrderId; // L2 内部生成的唯一ID
    public short accountId;      // 关键：指定用哪个账号
    public short symbolId;        // "BTC"
    public long priceE8;         // 60000_00000000L
    public long qtyE8;           // 100_000000L
    public short side;         // BUY/SELL
    public short exchangeId;  // 交易所id
    public int goodTilTimeInSeconds = 3*60;

    // === dYdX v4 相关字段（撤单 / 追踪用）===
    public int clobPairId;          // clobPairId（例如 1000001）
    public long orderFlags;         // orderFlags（例如 64）
    public long goodTilBlockTimeSec; // goodTilBlockTime（epoch seconds）

}