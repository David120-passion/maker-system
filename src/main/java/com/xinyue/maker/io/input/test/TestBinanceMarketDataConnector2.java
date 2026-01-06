package com.xinyue.maker.io.input.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmax.disruptor.RingBuffer;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.CoreEventType;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.common.SymbolRegistry;
import com.xinyue.maker.io.MarketDataConnector;
import org.agrona.collections.Long2LongHashMap;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 测试用的币安订单簿数据模拟器 V2。
 * 重新实现，维护本地订单簿缓存，正确处理吃单逻辑。
 */
public class TestBinanceMarketDataConnector2 implements MarketDataConnector {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    /** 价格变化模式 */
    public enum PriceMode {
        RANDOM,      // 随机波动
        TREND_UP,    // 上涨趋势
        TREND_DOWN,  // 下跌趋势
        OSCILLATE    // 震荡（正弦波）
    }
    
    private final RingBuffer<CoreEvent> ringBuffer;
    private final String symbol;
    private final short symbolId;
    
    // 模拟参数
    private final double basePrice;
    private final double priceSpread;
    private final int depthLevels;
    private final double tickSize;
    private final double baseQty;
    private final double bidAskSpread;
    private final PriceMode priceMode;
    private final double priceStepPerUpdate;
    
    // 线程控制
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread dataGeneratorThread;
    
    // 价格状态
    private double currentPrice;
    private double lastPrice;
    private int updateCount = 0;
    
    // 本地订单簿缓存（维护完整的订单簿状态，使用 Map 不受数组大小限制）
    private final Long2LongHashMap cachedBids = new Long2LongHashMap(0L); // price -> qty
    private final Long2LongHashMap cachedAsks = new Long2LongHashMap(0L); // price -> qty
    private boolean hasCachedOrderBook = false;
    
    // 随机数生成器
    private final Random random = new Random();
    
    public TestBinanceMarketDataConnector2(RingBuffer<CoreEvent> ringBuffer, String symbol, double basePrice, 
                                         double priceSpread, int depthLevels, double tickSize, 
                                         double baseQty, double bidAskSpread, PriceMode priceMode,
                                         double priceStepPerUpdate) {
        this.ringBuffer = ringBuffer;
        this.symbol = symbol;
        this.symbolId = SymbolRegistry.getInstance().get(symbol);
        this.basePrice = basePrice;
        this.priceSpread = priceSpread;
        this.depthLevels = Math.min(depthLevels, CoreEvent.MAX_DEPTH);
        this.tickSize = tickSize;
        this.baseQty = baseQty;
        this.bidAskSpread = bidAskSpread;
        this.priceMode = priceMode;
        this.priceStepPerUpdate = priceStepPerUpdate;
        this.currentPrice = basePrice;
        this.lastPrice = basePrice;
    }
    
    public TestBinanceMarketDataConnector2(RingBuffer<CoreEvent> ringBuffer, String symbol, double basePrice, 
                                         double tickSize, double baseQty) {
        this(ringBuffer, symbol, basePrice, 100.0, 10, tickSize, baseQty, 
             0.01, PriceMode.TREND_UP, 0.01);
    }
    
    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }
    
    @Override
    public boolean referenceOnly() {
        return true;
    }
    
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            dataGeneratorThread = new Thread(this::generateDepthUpdates, "TestBinanceDataGenerator2");
            dataGeneratorThread.setDaemon(true);
            dataGeneratorThread.start();
            System.out.println("TestBinanceMarketDataConnector2 started: symbol=" + symbol + ", basePrice=" + basePrice);
        }
    }
    
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (dataGeneratorThread != null) {
                try {
                    dataGeneratorThread.interrupt();
                    dataGeneratorThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("TestBinanceMarketDataConnector2 stopped");
        }
    }
    
    private void generateDepthUpdates() {
        long lastU = 0;
        
        while (running.get()) {
            try {
                updatePrice();
                
                long U = lastU + 1;
                long u = U + depthLevels - 1;
                lastU = u;
                
                publishDepthUpdateEvent(currentPrice, U, u);
                
                updateCount++;
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("生成测试数据失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    private void updatePrice() {
        switch (priceMode) {
            case RANDOM:
                currentPrice = basePrice + (random.nextDouble() - 0.5) * priceSpread;
                break;
            case TREND_UP:
                currentPrice += priceStepPerUpdate * (1.0 + random.nextDouble() * 0.5);
                if (currentPrice > basePrice + priceSpread) {
                    currentPrice = basePrice + priceSpread * 0.5;
                }
                break;
            case TREND_DOWN:
                currentPrice -= priceStepPerUpdate * (1.0 + random.nextDouble() * 0.5);
                if (currentPrice < basePrice - priceSpread) {
                    currentPrice = basePrice - priceSpread * 0.5;
                }
                break;
            case OSCILLATE:
                double oscillation = Math.sin(updateCount * 0.1) * priceSpread * 0.3;
                double noise = (random.nextDouble() - 0.5) * priceSpread * 0.2;
                currentPrice = basePrice + oscillation + noise;
                if (updateCount % 10 == 0) {
                    currentPrice += (random.nextBoolean() ? 1 : -1) * priceStepPerUpdate * 5;
                }
                break;
        }
    }
    
    private void publishDepthUpdateEvent(double midPrice, long U, long u) {
        if (ringBuffer == null) {
            return;
        }
        
        long seq = ringBuffer.next();
        try {
            CoreEvent event = ringBuffer.get(seq);
            event.reset();
            
            event.type = CoreEventType.DEPTH_UPDATE;
            event.exchangeId = Exchange.Test.id();
            event.symbolId = symbolId;
            event.accountId = 0;
            
            event.timestamp = System.currentTimeMillis();
            event.recvTime = System.nanoTime();
            
            event.firstUpdateId = U;
            event.sequence = u;
            
            // 计算买一和卖一价格（确保卖一 > 买一）
            double bestBidPrice = midPrice - bidAskSpread / 2.0;
            double bestAskPrice = midPrice + bidAskSpread / 2.0;
            
            bestBidPrice = Math.round(bestBidPrice * 100.0) / 100.0;
            bestAskPrice = Math.round(bestAskPrice * 100.0) / 100.0;
            
            if (bestAskPrice <= bestBidPrice) {
                bestAskPrice = bestBidPrice + tickSize;
                bestAskPrice = Math.round(bestAskPrice * 100.0) / 100.0;
            }
            
            int bidCount = Math.min(depthLevels, CoreEvent.MAX_DEPTH);
            int askCount = Math.min(depthLevels, CoreEvent.MAX_DEPTH);
            
            // 判断价格变化方向
            double priceChange = midPrice - lastPrice;
            boolean priceUp = priceChange > 0.0001;
            boolean priceDown = priceChange < -0.0001;
            
            // 第一步：填充 bids
            for (int i = 0; i < bidCount; i++) {
                double price = bestBidPrice - i * tickSize;
                price = Math.round(price * 100.0) / 100.0;
                long priceE8 = (long) (price * ScaleConstants.SCALE_E8);
                
                long qtyE8;
                if (hasCachedOrderBook) {
                    long cachedQty = cachedBids.get(priceE8);
                    if (cachedQty > 0) {
                        qtyE8 = cachedQty;
                    } else {
                        double depthFactor = Math.exp(-i * 0.2);
                        double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                        qty = Math.round(qty * 100.0) / 100.0;
                        qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                    }
                } else {
                    double depthFactor = Math.exp(-i * 0.2);
                    double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                    qty = Math.round(qty * 100.0) / 100.0;
                    qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                }
                
                event.bidPrices[i] = priceE8;
                event.bidQtys[i] = qtyE8;
            }
            
            // 获取当前买一价格（bids[0]）
            long currentBestBidPriceE8 = event.bidPrices[0];
            
            // 第二步：填充 asks
            for (int i = 0; i < askCount; i++) {
                double price = bestAskPrice + i * tickSize;
                price = Math.round(price * 100.0) / 100.0;
                long priceE8 = (long) (price * ScaleConstants.SCALE_E8);
                
                long qtyE8;
                if (hasCachedOrderBook) {
                    long cachedQty = cachedAsks.get(priceE8);
                    if (cachedQty > 0) {
                        qtyE8 = cachedQty;
                    } else {
                        double depthFactor = Math.exp(-i * 0.2);
                        double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                        qty = Math.round(qty * 100.0) / 100.0;
                        qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                    }
                } else {
                    double depthFactor = Math.exp(-i * 0.2);
                    double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                    qty = Math.round(qty * 100.0) / 100.0;
                    qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                }
                
                event.askPrices[i] = priceE8;
                event.askQtys[i] = qtyE8;
            }
            
            // 第三步：价格上涨时，扫描本地订单簿，把所有小于买1价格的卖单都置为0并填充到 event.asks
            if (hasCachedOrderBook && priceUp) {
                System.err.printf("[EATEN] 价格上涨，扫描本地订单簿 asks，当前买一价格=%.2f%n",
                    currentBestBidPriceE8 / (double) ScaleConstants.SCALE_E8);
                
                // 使用数组包装计数器，以便在 lambda 中修改
                final int[] askCountRef = {askCount};
                
                // 遍历本地订单簿 Map 的所有条目
                BiConsumer<Long, Long> askConsumer = (cachedPriceE8, cachedQtyE8) -> {
                    // 如果本地订单簿中 asks 价格 <= 当前买一价格，这个档位被吃掉，置为0
                    if (cachedPriceE8 <= currentBestBidPriceE8 && askCountRef[0] < CoreEvent.MAX_DEPTH) {
                        event.askPrices[askCountRef[0]] = cachedPriceE8;
                        event.askQtys[askCountRef[0]] = 0; // 置为0
                        askCountRef[0]++;
                        System.err.printf("[EATEN] asks 档位被吃掉: price=%.2f, qty -> 0%n",
                            cachedPriceE8 / (double) ScaleConstants.SCALE_E8);
                    }
                };
                cachedAsks.forEach(askConsumer);
                askCount = askCountRef[0];
            }
            
            // 价格下跌时，扫描本地订单簿，把所有大于卖1价格的买单都置为0并填充到 event.bids
            if (hasCachedOrderBook && priceDown) {
                long currentBestAskPriceE8 = event.askPrices[0];
                System.err.printf("[EATEN] 价格下跌，扫描本地订单簿 bids，当前卖一价格=%.2f%n",
                    currentBestAskPriceE8 / (double) ScaleConstants.SCALE_E8);
                
                // 使用数组包装计数器，以便在 lambda 中修改
                final int[] bidCountRef = {bidCount};
                
                // 遍历本地订单簿 Map 的所有条目
                BiConsumer<Long, Long> bidConsumer = (cachedPriceE8, cachedQtyE8) -> {
                    // 如果本地订单簿中 bids 价格 >= 当前卖一价格，这个档位被吃掉，置为0
                    if (cachedPriceE8 >= currentBestAskPriceE8 && bidCountRef[0] < CoreEvent.MAX_DEPTH) {
                        event.bidPrices[bidCountRef[0]] = cachedPriceE8;
                        event.bidQtys[bidCountRef[0]] = 0; // 置为0
                        bidCountRef[0]++;
                        System.err.printf("[EATEN] bids 档位被吃掉: price=%.2f, qty -> 0%n",
                            cachedPriceE8 / (double) ScaleConstants.SCALE_E8);
                    }
                };
                cachedBids.forEach(bidConsumer);
                bidCount = bidCountRef[0];
            }
            
            // 设置深度档位数量
            event.depthCount = Math.max(bidCount, askCount);
            
            // 更新本地订单簿缓存：将 event 中的所有档位都更新到 Map 中
            // 每一轮的所有行情都应该更新进去
            for (int i = 0; i < bidCount; i++) {
                long priceE8 = event.bidPrices[i];
                long qtyE8 = event.bidQtys[i];
                if (qtyE8 > 0) {
                    cachedBids.put(priceE8, qtyE8);
                } else {
                    // 数量为0的档位从缓存中移除
                    cachedBids.remove(priceE8);
                }
            }
            
            for (int i = 0; i < askCount; i++) {
                long priceE8 = event.askPrices[i];
                long qtyE8 = event.askQtys[i];
                if (qtyE8 > 0) {
                    cachedAsks.put(priceE8, qtyE8);
                } else {
                    // 数量为0的档位从缓存中移除
                    cachedAsks.remove(priceE8);
                }
            }
            
            hasCachedOrderBook = true;
            lastPrice = midPrice;
            
            // 打印 JSON
            println(event, bidCount, askCount);
            
        } catch (Exception e) {
            CoreEvent event = ringBuffer.get(seq);
            event.type = CoreEventType.NONE;
            System.err.println("生成测试数据失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            ringBuffer.publish(seq);
        }
    }
    
    private void println(CoreEvent event, int bidCount, int askCount) {
        try {
            ObjectNode json = OBJECT_MAPPER.createObjectNode();
            json.put("type", event.type != null ? event.type.name() : "UNKNOWN");
            json.put("timestamp", event.timestamp);
            json.put("recvTime", event.recvTime);
            json.put("sequence", event.sequence);
            json.put("firstUpdateId", event.firstUpdateId);
            json.put("exchangeId", event.exchangeId);
            json.put("symbolId", event.symbolId);
            json.put("accountId", event.accountId);
            json.put("depthCount", event.depthCount);
            
            ArrayNode bidsArray = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < event.depthCount && i < bidCount; i++) {
                ArrayNode level = OBJECT_MAPPER.createArrayNode();
                double price = event.bidPrices[i] / (double) ScaleConstants.SCALE_E8;
                level.add(Math.round(price * 100.0) / 100.0);
                double qty = event.bidQtys[i] == 0 ? 0.0 : event.bidQtys[i] / (double) ScaleConstants.SCALE_E8;
                level.add(qty == 0.0 ? 0.0 : Math.round(qty * 100.0) / 100.0);
                bidsArray.add(level);
            }
            json.set("bids", bidsArray);
            
            ArrayNode asksArray = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < event.depthCount && i < askCount; i++) {
                ArrayNode level = OBJECT_MAPPER.createArrayNode();
                double price = event.askPrices[i] / (double) ScaleConstants.SCALE_E8;
                level.add(Math.round(price * 100.0) / 100.0);
                double qty = event.askQtys[i] == 0 ? 0.0 : event.askQtys[i] / (double) ScaleConstants.SCALE_E8;
                level.add(qty == 0.0 ? 0.0 : Math.round(qty * 100.0) / 100.0);
                asksArray.add(level);
            }
            json.set("asks", asksArray);
            
            System.out.println(OBJECT_MAPPER.writeValueAsString(json));
        } catch (Exception e) {
            System.err.println("JSON 序列化失败: " + e.getMessage());
        }
    }
}

