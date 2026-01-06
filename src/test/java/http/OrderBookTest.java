package http;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongRBTreeMap;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("fastutil Long2LongRBTreeMap 订单簿测试")
class OrderBookTest {

    // 卖单簿：价格从低到高 (默认升序)
    private Long2LongRBTreeMap asks;
    
    // 买单簿：价格从高到低 (使用反向比较器) -> 强烈推荐做法
    private Long2LongRBTreeMap bids;

    // 为了演示方便，假设价格放大了 100 倍 (例如 10000 代表 100.00 元)
    // 数量单位假设为“手”或“个”
    private static void fixConsoleEncoding() {
        try {
            // 设置系统属性（部分 JVM 需要）
            System.setProperty("file.encoding", "UTF-8");
            System.setProperty("sun.jnu.encoding", "UTF-8");

            // 强制设置 System.out 和 System.err 为 UTF-8 编码
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 如果设置失败，不影响程序运行，只是可能还有乱码
            System.err.println("Warning: Failed to set console encoding to UTF-8: " + e.getMessage());
        }
    }
    @BeforeEach
    void setUp() {
        fixConsoleEncoding();
        // 初始化卖单簿 (默认升序)
        asks = new Long2LongRBTreeMap();
        
        // 初始化买单簿 (降序)
        // 使用 fastutil 提供的反向比较器，避免 Lambda 的微小开销
        bids = new Long2LongRBTreeMap(LongComparators.OPPOSITE_COMPARATOR);

        // 预填一些初始数据用于测试
        // 卖单数据：100.50(10), 101.00(20), 100.80(5)
        // 预期卖盘顺序(升序): 10050 -> 10080 -> 10100
        asks.put(10050L, 10L);
        asks.put(10100L, 20L);
        asks.put(10080L, 5L);

        // 买单数据：100.20(15), 99.50(50), 100.00(30)
        // 预期买盘顺序(降序): 10020 -> 10000 -> 9950
        bids.put(10020L, 15L);
        bids.put(9950L, 50L);
        bids.put(10000L, 30L);
    }

    @Test
    @DisplayName("测试获取 BBO (买一卖一价)")
    void testGetBBO() {
        // 验证卖一价 (Best Ask)：应该是最低的卖出价格
        assertFalse(asks.isEmpty(), "卖单簿不应为空");
        long bestAskPrice = asks.firstLongKey(); // 升序的第一个就是最低价
        assertEquals(10050L, bestAskPrice, "卖一价应该是 10050");
        assertEquals(10L, asks.get(bestAskPrice), "卖一量应该是 10");

        // 验证买一价 (Best Bid)：应该是最高的买入价格
        assertFalse(bids.isEmpty(), "买单簿不应为空");
        // 关键点：因为 bids 配置了降序比较器，所以最高的买入价也是第一个 Key！
        long bestBidPrice = bids.firstLongKey(); 
        assertEquals(10020L, bestBidPrice, "买一价应该是 10020");
        assertEquals(15L, bids.get(bestBidPrice), "买一量应该是 15");

        // 验证盘口状态：卖一价应该大于买一价 (无交叉)
        assertTrue(bestAskPrice > bestBidPrice, "盘口不应交叉");
    }

    @Test
    @DisplayName("测试订单簿的有序性 (遍历顺序)")
    void testOrderBookSorting() {
        System.out.println("--- 开始验证卖单簿顺序 (应为升序) ---");
        long lastPrice = Long.MIN_VALUE;
        // 推荐的遍历方式：使用 EntrySet 迭代器，零垃圾
        ObjectIterator<Long2LongMap.Entry> askIter = asks.long2LongEntrySet().iterator();
        while (askIter.hasNext()) {
            Long2LongMap.Entry entry = askIter.next();
            long currentPrice = entry.getLongKey();
            long quantity = entry.getLongValue();
            System.out.printf("Ask: Price=%d, Qty=%d%n", currentPrice, quantity);

            // 断言：当前价格必须大于上一个价格
            assertTrue(currentPrice > lastPrice, "卖单顺序错误");
            lastPrice = currentPrice;
        }

        System.out.println("\n--- 开始验证买单簿顺序 (应为降序) ---");
        lastPrice = Long.MAX_VALUE;
        ObjectIterator<Long2LongMap.Entry> bidIter = bids.long2LongEntrySet().iterator();
        while (bidIter.hasNext()) {
            Long2LongMap.Entry entry = bidIter.next();
            long currentPrice = entry.getLongKey();
            long quantity = entry.getLongValue();
            System.out.printf("Bid: Price=%d, Qty=%d%n", currentPrice, quantity);

            // 断言：当前价格必须小于上一个价格
            assertTrue(currentPrice < lastPrice, "买单顺序错误");
            lastPrice = currentPrice;
        }
    }

    @Test
    @DisplayName("测试订单更新与删除 (Put/Remove)")
    void testUpdateAndRemove() {
        // 1. 测试更新：增加卖一的数量
        long bestAskPrice = asks.firstLongKey();
        long oldQty = asks.get(bestAskPrice); // 10
        long newQty = oldQty + 50; // 变成 60
        asks.put(bestAskPrice, newQty);

        assertEquals(60L, asks.get(bestAskPrice), "更新后的数量不正确");

        // 2. 测试新增：插入一个更优的买单 (新的买一)
        long newBestBidPrice = 10030L;
        bids.put(newBestBidPrice, 100L);

        // 验证新的买一价是否生效
        assertEquals(newBestBidPrice, bids.firstLongKey(), "新的买一价未生效");
        assertEquals(4, bids.size(), "买单簿档位数应该增加");

        // 3. 测试删除：买单完全成交，数量变为 0，应该从 Map 中移除
        // 模拟逻辑：如果 update 数量为 0，则调用 remove
        long priceToRemove = 9950L;
        assertTrue(bids.containsKey(priceToRemove));
        
        // 执行删除操作
        long removedQty = bids.remove(priceToRemove);
        
        assertEquals(50L, removedQty, "移除的数量不正确");
        assertFalse(bids.containsKey(priceToRemove), "价格的数量为 0 后应被移除");
        assertEquals(3, bids.size(), "移除后档位数应减少");
    }

    @Test
    @DisplayName("模拟场景：计算吃掉 20 个卖单后的价格 (扫单测试)")
    void testSweepAsks() {
        // 当前卖单: 10050(10), 10080(5), 10100(20)
        long targetQuantityToEat = 20L;
        long eatenQuantity = 0L;
        long lastTradedPrice = 0L;

        ObjectIterator<Long2LongMap.Entry> iterator = asks.long2LongEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            long price = entry.getLongKey();
            long availableQty = entry.getLongValue();

            long canEat = Math.min(targetQuantityToEat - eatenQuantity, availableQty);
            
            eatenQuantity += canEat;
            lastTradedPrice = price;
            System.out.printf("扫单中: 在价格 %d 吃掉 %d 个, 总共吃掉 %d 个%n", price, canEat, eatenQuantity);

            if (eatenQuantity >= targetQuantityToEat) {
                // 吃够了
                break;
            }
        }

        assertEquals(20L, eatenQuantity, "应该正好吃掉 20 个");
        // 分析：
        // 1档 10050 吃掉 10个，还差 10个。
        // 2档 10080 吃掉 5个，还差 5个。
        // 3档 10100 吃掉 5个，吃够了。
        // 所以最后一笔成交价应该是 10100。
        assertEquals(10100L, lastTradedPrice, "最后一笔成交价计算错误");
    }
}