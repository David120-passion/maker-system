package com.xinyue.maker.core.lob;

/**
 * 本地订单簿接口。
 * <p>
 * 定义了订单簿维护的核心方法，支持多种实现：
 * - {@link LocalOrderBookHashMap}: 使用 HashMap 实现（无序，快速插入/删除）
 * - {@link LocalOrderBookRBTree}: 使用 RBTree 实现（有序，快速查询最值）
 */
public interface ILocalOrderBook {

    /**
     * 重置本地订单簿。
     */
    void reset();

    /**
     * 应用 dYdX 风格的全量快照（无 updateId）。
     */
    void applyDydxSnapshot(long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                           long[] askPricesE8, long[] askQtysE8, int askCount);

    /**
     * 应用 Binance 风格的快照。
     */
    void applySnapshot(long lastUpdateId,
                       long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                       long[] askPricesE8, long[] askQtysE8, int askCount);

    /**
     * 应用 dYdX 风格的增量更新（无 updateId，直接应用）。
     */
    void applyDydxIncrementalUpdate(long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                                     long[] askPricesE8, long[] askQtysE8, int askCount);

    /**
     * 应用单个 depthUpdate 事件，遵循 Binance 官方的本地订单簿维护规则。
     *
     * @param firstUpdateId U：该事件的首次更新 ID
     * @param lastUpdateId  u：该事件的最后一次更新 ID
     * @param bidPricesE8   买盘价数组（放大 1e8）
     * @param bidQtysE8     买盘量数组（放大 1e8）
     * @param bidCount      买盘档位数量
     * @param askPricesE8   卖盘价数组（放大 1e8）
     * @param askQtysE8     卖盘量数组（放大 1e8）
     * @param askCount      卖盘档位数量
     * @return true 表示成功应用该事件；false 表示检测到丢包，需要丢弃本地订单簿并重新从快照开始
     */
    boolean applyEvent(long firstUpdateId, long lastUpdateId,
                       long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                       long[] askPricesE8, long[] askQtysE8, int askCount);

    /**
     * 应用测试事件。
     */
    boolean applyTestEvent(long firstUpdateId, long lastUpdateId,
                           long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                           long[] askPricesE8, long[] askQtysE8, int askCount);

    /**
     * 返回当前本地订单簿的 updateId。
     */
    long updateId();

    /**
     * 计算当前最优买价（best bid）。
     *
     * @return 最优买价（放大 10^8 倍），如果没有买盘则返回 0
     */
    long bestBidE8();

    /**
     * 计算当前最优卖价（best ask）。
     *
     * @return 最优卖价（放大 10^8 倍），如果没有卖盘则返回 0
     */
    long bestAskE8();

    /**
     * 返回买一价的深度数量（放大 1e8）。
     *
     * @return 买一价的数量，如果没有买盘则返回 0
     */
    long bestBidQtyE8();

    /**
     * 返回卖一价的深度数量（放大 1e8）。
     *
     * @return 卖一价的数量，如果没有卖盘则返回 0
     */
    long bestAskQtyE8();

    /**
     * 计算从最低价到目标价之间的累计卖单数量。
     * <p>
     * 遍历订单簿的 asks，找出所有价格在 [minPriceE8, maxPriceE8] 范围内的订单，累计它们的数量。
     * 
     * @param minPriceE8 最低价格（包含）
     * @param maxPriceE8 最高价格（包含）
     * @return 累计数量（放大 10^8 倍）
     */
    long calculateCumulativeAskQty(long minPriceE8, long maxPriceE8);

    /**
     * 计算从最高价到目标价之间的累计买单数量。
     * <p>
     * 遍历订单簿的 bids，找出所有价格在 [minPriceE8, maxPriceE8] 范围内的订单，累计它们的数量。
     * 
     * @param maxPriceE8 最高价格（包含）
     * @param minPriceE8 最低价格（包含）
     * @return 累计数量（放大 10^8 倍）
     */
    long calculateCumulativeBidQty(long maxPriceE8, long minPriceE8);
}

