package com.xinyue.maker.core.lob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.core.TestEventScheduler;
import it.unimi.dsi.fastutil.longs.Long2LongRBTreeMap;
import it.unimi.dsi.fastutil.longs.LongComparators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地订单簿实现 - 使用 RBTree（有序，优化版本）。
 * <p>
 * 实现了 Binance 文档风格的快照 + 增量更新算法：
 * - 使用 REST 快照初始化（lastUpdateId）
 * - 使用 [U, u, bids, asks] 事件做增量维护
 * <p>
 * 特点：
 * - 使用 fastutil 的 Long2LongRBTreeMap（红黑树），保持价格有序
 * - 买单使用降序排列，卖单使用升序排列
 * - bestBid/bestAsk 查询从 O(n) 优化到 O(1)
 */
public final class LocalOrderBookRBTree implements ILocalOrderBook {
    private static final Logger LOG = LoggerFactory.getLogger(LocalOrderBookRBTree.class);
    /**
     * 当前本地订单簿的 updateId（等价于官方文档中的 lastUpdateId）。
     */
    private long updateId;

    /**
     * 买盘与卖盘：key = priceE8, value = qtyE8。
     * <p>
     * 使用 fastutil 的 Long2LongRBTreeMap（红黑树），保持价格有序：
     * - bids（买单）：降序排列（使用反向比较器），第一个元素即为 bestBid（最高买价）
     * - asks（卖单）：升序排列（默认），第一个元素即为 bestAsk（最低卖价）
     * <p>
     * 优势：
     * 1. 有序性：可以直接获取最值，无需遍历
     * 2. 支持按价格范围查询，便于计算累计数量
     * 3. 时间复杂度：O(log n) 插入/删除，O(1) 获取最值
     */
    private final Long2LongRBTreeMap bids = new Long2LongRBTreeMap(LongComparators.OPPOSITE_COMPARATOR);
    private final Long2LongRBTreeMap asks = new Long2LongRBTreeMap();

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
                try {
                    LOG.info("买单移除价格{},数量:{}",price,objectMap.writeValueAsString(bidPricesE8));
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            } else {
                bids.put(price, qty);
                try {
                    LOG.info("买单更新价格{},数量:{}",price,objectMap.writeValueAsString(bidPricesE8));
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            }
        }

        // 卖盘：数量为 0 删除该档，否则插入/更新
        for (int i = 0; i < askCount; i++) {
            long price = askPricesE8[i];
            long qty = askQtysE8[i];
            if (qty == 0) {
                asks.remove(price);
                try {
                    LOG.info("卖单移除价格{},数量:{}",price,objectMap.writeValueAsString(bidPricesE8));
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            } else {
                asks.put(price, qty);
                try {
                    LOG.info("卖单更新价格{},数量:{}",price,objectMap.writeValueAsString(bidPricesE8));
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
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
     * 由于 bids 使用降序排列（最高价在前），直接取第一个元素即可。
     * 时间复杂度：O(1)
     */
    @Override
    public long bestBidE8() {
        if (bids.isEmpty()) {
            return 0L;
        }
        long firstKey = bids.firstLongKey();
        long qty = bids.get(firstKey);
        return qty > 0 ? firstKey : 0L;
    }

    /**
     * 计算当前最优卖价（best ask）。
     * <p>
     * 由于 asks 使用升序排列（最低价在前），直接取第一个元素即可。
     * 时间复杂度：O(1)
     */
    @Override
    public long bestAskE8() {
        if (asks.isEmpty()) {
            return 0L;
        }
        long firstKey = asks.firstLongKey();
        long qty = asks.get(firstKey);
        return qty > 0 ? firstKey : 0L;
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
     * @return 卖单簿的 RBTreeMap
     */
    public Long2LongRBTreeMap getAsks() {
        return asks;
    }

    /**
     * 获取买单簿（用于需要直接访问底层数据结构的场景）。
     * 
     * @return 买单簿的 RBTreeMap
     */
    public Long2LongRBTreeMap getBids() {
        return bids;
    }

    /**
     * 计算从最低价到目标价之间的累计卖单数量。
     * <p>
     * 对于 RBTree 实现，由于 asks 是升序排列的（最低价在前），可以从第一个元素开始遍历，
     * 一旦价格超过 maxPriceE8 就可以提前退出，显著提高效率。
     * 
     * @param minPriceE8 最低价格（包含）
     * @param maxPriceE8 最高价格（包含）
     * @return 累计数量（放大 10^8 倍）
     */
    @Override
    public long calculateCumulativeAskQty(long minPriceE8, long maxPriceE8) {
        long sum = 0L;
        // 由于 asks 是升序排列的，从最低价开始遍历，遇到价格超过 maxPriceE8 时提前退出
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : asks.long2LongEntrySet()) {
            long price = entry.getLongKey();
            // 价格已超过最大值，提前退出
            if (price > maxPriceE8) {
                break;
            }
            // 价格在范围内且数量大于 0，累加
            if (price >= minPriceE8) {
                long qty = entry.getLongValue();
                if (qty > 0) {
                    sum += qty;
                }
            }
        }
        if(sum / (double) ScaleConstants.SCALE_E8 > 100){
            LOG.info("异常订单，订单簿情况：{}",asks.toString());
        }
        return sum;
    }

    /**
     * 计算从最高价到目标价之间的累计买单数量。
     * <p>
     * 对于 RBTree 实现，由于 bids 是降序排列的（最高价在前），可以从第一个元素开始遍历，
     * 一旦价格低于 minPriceE8 就可以提前退出，显著提高效率。
     * 
     * @param maxPriceE8 最高价格（包含）
     * @param minPriceE8 最低价格（包含）
     * @return 累计数量（放大 10^8 倍）
     */
    @Override
    public long calculateCumulativeBidQty(long maxPriceE8, long minPriceE8) {
        long sum = 0L;
        // 由于 bids 是降序排列的（最高价在前），从最高价开始遍历，遇到价格低于 minPriceE8 时提前退出
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : bids.long2LongEntrySet()) {
            long price = entry.getLongKey();
            // 价格已低于最小值，提前退出
            if (price < minPriceE8) {
                break;
            }
            // 价格在范围内且数量大于 0，累加
            if (price <= maxPriceE8) {
                long qty = entry.getLongValue();
                if (qty > 0) {
                    sum += qty;
                }
            }
        }
        if(sum / (double) ScaleConstants.SCALE_E8 > 100){
            LOG.info("异常订单，订单簿情况：{}",bids.toString());
        }
        return sum;
    }
}

