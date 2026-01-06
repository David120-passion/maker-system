package com.xinyue.maker.core.lob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.agrona.collections.Long2LongHashMap;

/**
 * 本地订单簿实现 - 使用 HashMap（无序）。
 * <p>
 * 实现了 Binance 文档风格的快照 + 增量更新算法：
 * - 使用 REST 快照初始化（lastUpdateId）
 * - 使用 [U, u, bids, asks] 事件做增量维护
 * <p>
 * 特点：
 * - 使用 Agrona 的 Long2LongHashMap，避免装箱与多余 GC
 * - 插入/删除操作快（O(1)）
 * - 获取最值需要遍历（O(n)）
 */
public final class LocalOrderBookHashMap implements ILocalOrderBook {

    /**
     * 当前本地订单簿的 updateId（等价于官方文档中的 lastUpdateId）。
     */
    private long updateId;

    /**
     * 买盘与卖盘：key = priceE8, value = qtyE8。
     * <p>
     * 使用 Agrona 的 Long2LongHashMap，避免装箱与多余 GC。
     * 默认缺省值为 0，表示该价位不存在或数量为 0。
     */
    private final Long2LongHashMap bids = new Long2LongHashMap(0L);
    private final Long2LongHashMap asks = new Long2LongHashMap(0L);

    private static final ObjectMapper objectMap = new ObjectMapper();

    /**
     * 重置本地订单簿。
     */
    @Override
    public void reset() {
        updateId = 0;
        bids.clear();
        asks.clear();
    }

    /**
     * 应用 dYdX 风格的全量快照（无 updateId）。
     */
    @Override
    public void applyDydxSnapshot(long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                                   long[] askPricesE8, long[] askQtysE8, int askCount) {
        reset();
        updateId = 1; // 标记为已初始化

        for (int i = 0; i < bidCount; i++) {
            long price = bidPricesE8[i];
            long qty = bidQtysE8[i];
            if (qty > 0) {
                bids.put(price, qty);
            }
        }

        for (int i = 0; i < askCount; i++) {
            long price = askPricesE8[i];
            long qty = askQtysE8[i];
            if (qty > 0) {
                asks.put(price, qty);
            }
        }
    }

    @Override
    public void applySnapshot(long lastUpdateId,
                              long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                              long[] askPricesE8, long[] askQtysE8, int askCount) {
        reset();

        for (int i = 0; i < bidCount; i++) {
            long price = bidPricesE8[i];
            long qty = bidQtysE8[i];
            if (qty > 0) {
                bids.put(price, qty);
            }
        }

        for (int i = 0; i < askCount; i++) {
            long price = askPricesE8[i];
            long qty = askQtysE8[i];
            if (qty > 0) {
                asks.put(price, qty);
            }
        }

        this.updateId = lastUpdateId;
    }

    /**
     * 应用 dYdX 风格的增量更新（无 updateId，直接应用）。
     * <p>
     * dYdX 增量更新规则：
     * - 数量为 0 表示删除该价位
     * - 数量 > 0 表示插入或更新该价位
     */
    @Override
    public void applyDydxIncrementalUpdate(long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                                           long[] askPricesE8, long[] askQtysE8, int askCount) {
        // 买盘：数量为 0 删除该档，否则插入/更新
        for (int i = 0; i < bidCount; i++) {
            long price = bidPricesE8[i];
            long qty = bidQtysE8[i];
            if (qty == 0) {
                bids.remove(price);
            } else {
                bids.put(price, qty);
            }
        }

        // 卖盘：数量为 0 删除该档，否则插入/更新
        for (int i = 0; i < askCount; i++) {
            long price = askPricesE8[i];
            long qty = askQtysE8[i];
            if (qty == 0) {
                asks.remove(price);
            } else {
                asks.put(price, qty);
            }
        }
    }

    /**
     * 应用单个 depthUpdate 事件，遵循 Binance 官方的本地订单簿维护规则。
     */
    @Override
    public boolean applyEvent(long firstUpdateId, long lastUpdateId,
                              long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                              long[] askPricesE8, long[] askQtysE8, int askCount) {
        // 尚未完成快照初始化时，不应该直接应用事件
        if (updateId == 0) {
            return false;
        }

        // 1. 若事件的最后一次更新 ID 小于本地 updateId，忽略
        if (lastUpdateId < this.updateId) {
            return true;
        }

        // 2. 若事件的首次更新 ID 大于本地 updateId + 1，说明中间有缺失，必须重建
        if (firstUpdateId > this.updateId + 1) {
            return false;
        }

        // 3. 正常更新流程：按价格逐档更新
        // 买盘：数量为 0 删除该档，否则插入/更新
        for (int i = 0; i < bidCount; i++) {
            long price = bidPricesE8[i];
            long qty = bidQtysE8[i];
            if (qty == 0) {
                bids.remove(price);
            } else {
                bids.put(price, qty);
            }
        }

        // 卖盘：数量为 0 删除该档，否则插入/更新
        for (int i = 0; i < askCount; i++) {
            long price = askPricesE8[i];
            long qty = askQtysE8[i];
            if (qty == 0) {
                asks.remove(price);
            } else {
                asks.put(price, qty);
            }
        }

        // 4. 将本地 updateId 更新为事件的 u
        this.updateId = lastUpdateId;

        return true;
    }

    @Override
    public boolean applyTestEvent(long firstUpdateId, long lastUpdateId,
                              long[] bidPricesE8, long[] bidQtysE8, int bidCount,
                              long[] askPricesE8, long[] askQtysE8, int askCount) {
        // 1. 若事件的最后一次更新 ID 小于本地 updateId，忽略
        if (lastUpdateId < this.updateId) {
            return true;
        }

        // 2. 若事件的首次更新 ID 大于本地 updateId + 1，说明中间有缺失，必须重建
        if (firstUpdateId > this.updateId + 1) {
            return false;
        }

        // 3. 正常更新流程：按价格逐档更新
        // 买盘：数量为 0 删除该档，否则插入/更新
        for (int i = 0; i < bidCount; i++) {
            long price = bidPricesE8[i];
            long qty = bidQtysE8[i];
            if (qty == 0) {
                bids.remove(price);
            } else {
                bids.put(price, qty);
            }
        }

        // 卖盘：数量为 0 删除该档，否则插入/更新
        for (int i = 0; i < askCount; i++) {
            long price = askPricesE8[i];
            long qty = askQtysE8[i];
            if (qty == 0) {
                asks.remove(price);
            } else {
                asks.put(price, qty);
            }
        }

        // 4. 将本地 updateId 更新为事件的 u
        this.updateId = lastUpdateId;

        return true;
    }

    /**
     * 返回当前本地订单簿的 updateId。
     */
    @Override
    public long updateId() {
        return updateId;
    }

    /**
     * 计算当前最优买价（best bid）。
     * <p>
     * 为了避免引入额外数据结构，这里简单遍历一次所有买盘价位。
     * 后续可以根据性能需求改成有序数组/堆等结构。
     */
    @Override
    public long bestBidE8() {
        final long[] best = {0L};
        bids.forEach((price, qty) -> {
            if (qty > 0 && price > best[0]) {
                best[0] = price;
            }
        });
        return best[0];
    }

    /**
     * 计算当前最优卖价（best ask）。
     */
    @Override
    public long bestAskE8() {
        final long[] best = {0L};
        asks.forEach((price, qty) -> {
            if (qty > 0 && (best[0] == 0 || price < best[0])) {
                best[0] = price;
            }
        });
        return best[0];
    }

    /**
     * 返回买一价的深度数量（放大 1e8）。
     */
    @Override
    public long bestBidQtyE8() {
        long bestBid = bestBidE8();
        if (bestBid == 0) {
            return 0L;
        }
        return bids.get(bestBid);
    }

    /**
     * 返回卖一价的深度数量（放大 1e8）。
     */
    @Override
    public long bestAskQtyE8() {
        long bestAsk = bestAskE8();
        if (bestAsk == 0) {
            return 0L;
        }
        return asks.get(bestAsk);
    }

    /**
     * 获取卖单簿（用于需要直接访问底层数据结构的场景）。
     * 
     * @return 卖单簿的 HashMap
     */
    public Long2LongHashMap getAsks() {
        return asks;
    }

    /**
     * 获取买单簿（用于需要直接访问底层数据结构的场景）。
     * 
     * @return 买单簿的 HashMap
     */
    public Long2LongHashMap getBids() {
        return bids;
    }

    /**
     * 计算从最低价到目标价之间的累计卖单数量。
     * <p>
     * 对于 HashMap 实现，需要遍历所有卖单条目（O(n) 复杂度）。
     * 
     * @param minPriceE8 最低价格（包含）
     * @param maxPriceE8 最高价格（包含）
     * @return 累计数量（放大 10^8 倍）
     */
    @Override
    public long calculateCumulativeAskQty(long minPriceE8, long maxPriceE8) {
        final long[] sum = {0L};
        asks.forEach((price, qty) -> {
            if (price >= minPriceE8 && price <= maxPriceE8 && qty > 0) {
                sum[0] += qty;
            }
        });
        return sum[0];
    }

    /**
     * 计算从最高价到目标价之间的累计买单数量。
     * <p>
     * 对于 HashMap 实现，需要遍历所有买单条目（O(n) 复杂度）。
     * 
     * @param maxPriceE8 最高价格（包含）
     * @param minPriceE8 最低价格（包含）
     * @return 累计数量（放大 10^8 倍）
     */
    @Override
    public long calculateCumulativeBidQty(long maxPriceE8, long minPriceE8) {
        final long[] sum = {0L};
        bids.forEach((price, qty) -> {
            if (price >= minPriceE8 && price <= maxPriceE8 && qty > 0) {
                sum[0] += qty;
            }
        });
        return sum[0];
    }
}

