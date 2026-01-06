package com.xinyue.maker.io.input.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.CoreEventType;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.common.SymbolRegistry;
import com.xinyue.maker.core.CoreEventFactory;
import com.xinyue.maker.io.MarketDataConnector;

import java.util.Random;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用的币安订单簿数据模拟器。
 * 直接生成 CoreEvent 并推送到队列，避免 JSON 解析，提高效率。
 * <p>
 * 优化点：
 * 1. 生成符合策略阈值要求的价格变化（确保能触发 PriceFollowingStrategy）
 * 2. 确保买一卖一之间有合理的价差（满足最小价差要求）
 * 3. 支持价格趋势模式（上涨、下跌、震荡）
 * 4. 生成真实的订单簿深度数据
 * 5. 直接构建 CoreEvent，零 GC，高性能
 */
public class TestBinanceMarketDataConnector implements MarketDataConnector {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    /** 价格变化模式 */
    public enum PriceMode {
        RANDOM,      // 随机波动
        TREND_UP,    // 上涨趋势
        TREND_DOWN,  // 下跌趋势
        OSCILLATE    // 震荡（正弦波）
    }
    
    private final RingBuffer<CoreEvent> ringBuffer;
    private final String symbol; // 交易对，如 "BTCUSDT"
    private final short symbolId;
    
    // 模拟参数
    private final double basePrice;          // 基础价格（如 60000.0）
    private final double priceSpread;        // 价格波动范围（如 100.0）
    private final int depthLevels;           // 订单簿深度档位数量
    private final double tickSize;           // 价格步长（如 0.01）
    private final double baseQty;            // 基础数量（如 0.1）
    private final double bidAskSpread;       // 买一卖一价差（如 0.001，即 100_000L in E8）
    private final PriceMode priceMode;       // 价格变化模式
    private final double priceStepPerUpdate; // 每次更新的价格变化步长（确保能触发阈值 10_000L = 0.0001）
    
    // 线程控制
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread dataGeneratorThread;
    
    // 价格状态（用于趋势/震荡模式）
    private double currentPrice;
    private double lastPrice; // 上一次的价格，用于判断价格变化方向
    private int updateCount = 0;
    
    // 上一次订单簿状态（用于模拟吃单过程）
    private final long[] lastBidPrices = new long[CoreEvent.MAX_DEPTH];
    private final long[] lastBidQtys = new long[CoreEvent.MAX_DEPTH];
    private final long[] lastAskPrices = new long[CoreEvent.MAX_DEPTH];
    private final long[] lastAskQtys = new long[CoreEvent.MAX_DEPTH];
    private int lastBidCount = 0; // 上一次订单簿的 bid 档位数
    private int lastAskCount = 0; // 上一次订单簿的 ask 档位数
    private boolean hasLastOrderBook = false; // 是否已有上一次订单簿数据
    
    // 随机数生成器
    private final Random random = new Random();
    
    /**
     * 完整构造器
     */
    public TestBinanceMarketDataConnector(RingBuffer<CoreEvent> ringBuffer, String symbol, double basePrice, 
                                         double priceSpread, int depthLevels, double tickSize, 
                                         double baseQty, double bidAskSpread, PriceMode priceMode,
                                         double priceStepPerUpdate) {
        this.ringBuffer = ringBuffer;
        this.symbol = symbol;
        this.symbolId = SymbolRegistry.getInstance().get(symbol);
        this.basePrice = basePrice;
        this.priceSpread = priceSpread;
        this.depthLevels = Math.min(depthLevels, CoreEvent.MAX_DEPTH); // 不能超过 MAX_DEPTH
        this.tickSize = tickSize;
        this.baseQty = baseQty;
        this.bidAskSpread = bidAskSpread;
        this.priceMode = priceMode;
        this.priceStepPerUpdate = priceStepPerUpdate;
        this.currentPrice = basePrice;
        this.lastPrice = basePrice;
    }

    /**
     * 测试主方法：用于单独测试数据生成器。
     * 创建完整的 Disruptor 环境，生成数据并消费验证。
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("========== 启动测试环境 ==========");
        
        // 1. 创建 Disruptor 和 RingBuffer
        ThreadFactory threadFactory = Thread.ofVirtual().name("test-disruptor-", 0).factory();
        Disruptor<CoreEvent> disruptor = new Disruptor<>(
            new CoreEventFactory(),
            1024,  // RingBuffer 大小
            threadFactory
        );
        
        // 2. 创建事件处理器（用于验证消费的数据）
        AtomicLong eventCount = new AtomicLong(0);
        EventHandler<CoreEvent> testHandler = (event, sequence, endOfBatch) -> {
            if (event.type == CoreEventType.DEPTH_UPDATE) {
                long count = eventCount.incrementAndGet();
                if (count % 10 == 0) { // 每 10 条打印一次
//                    System.out.printf("[Consumer] 已消费 %d 条事件, U=%d, u=%d, bestBid=%.2f, bestAsk=%.2f%n",
//                        count, event.firstUpdateId, event.sequence,
//                        event.bidPrices[0] / (double) ScaleConstants.SCALE_E8,
//                        event.askPrices[0] / (double) ScaleConstants.SCALE_E8);
                }
            }
            event.reset(); // 重置事件对象
        };
        
        disruptor.handleEventsWith(testHandler);
        
        // 3. 启动 Disruptor
        RingBuffer<CoreEvent> ringBuffer = disruptor.getRingBuffer();
        disruptor.start();
        System.out.println("Disruptor 已启动，RingBuffer 大小: 1024");
        
        // 4. 创建并启动数据生成器
        TestBinanceMarketDataConnector connector = new TestBinanceMarketDataConnector(
            ringBuffer,
            "BTCUSDT",
            60000.0,  // 基础价格
            0.01,     // tickSize
            0.1       // baseQty
        );
        connector.start();
        
        // 5. 运行指定时间
        int runSeconds = 10;
        System.out.printf("数据生成器已启动，将运行 %d 秒...%n", runSeconds);
        System.out.println("每 100ms 生成一条订单簿更新，每 10 条事件打印一次消费日志");
        System.out.println("----------------------------------------");
        
        Thread.sleep(runSeconds * 1000L);
        
        // 6. 停止
        connector.stop();
        disruptor.shutdown();
        
        System.out.println("----------------------------------------");
        System.out.printf("测试完成！共生成并消费了 %d 条订单簿更新事件%n", eventCount.get());
        System.out.println("========== 测试环境已关闭 ==========");
    }
    /**
     * 简化构造器（使用默认参数）
     */
    public TestBinanceMarketDataConnector(RingBuffer<CoreEvent> ringBuffer, String symbol, double basePrice, 
                                         double tickSize, double baseQty) {
        this(ringBuffer, symbol, basePrice, 100.0, 10, tickSize, baseQty, 
             0.001, PriceMode.TREND_UP, 0.01);
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
            dataGeneratorThread = new Thread(this::generateDepthUpdates, "TestBinanceDataGenerator");
            dataGeneratorThread.setDaemon(true);
            dataGeneratorThread.start();

            System.out.println("TestBinanceMarketDataConnector started: symbol=" + symbol + ", basePrice=" + basePrice);
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
            System.out.println("TestBinanceMarketDataConnector stopped");
        }
    }
    
    /**
     * 生成深度更新数据的线程。
     * 每 100ms 生成一次订单簿更新，模拟真实市场行情。
     */
    private void generateDepthUpdates() {
        // 初始化：第一条消息的 U 从 1 开始
        long lastU = 0;
        
        while (running.get()) {
            try {
                // 根据价格模式生成当前价格
                updatePrice();
                
                // 生成 U 和 u（币安的序列号）
                // Binance 规范：下一条的 U = 上一条的 u + 1
                long U = lastU + 1;
                long u = U + depthLevels - 1; // u = U + (档位数 - 1)，模拟连续更新
                lastU = u; // 保存当前 u，用于下一条的 U
                
                // 直接构建并发布 CoreEvent
                publishDepthUpdateEvent(currentPrice, U, u);
                
                updateCount++;
                
                // 等待 100ms 再生成下一条
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
    
    /**
     * 根据价格模式更新当前价格。
     * 确保价格变化能满足策略的价格变化阈值（10_000L = 0.0001 USDT）。
     */
    private void updatePrice() {
        switch (priceMode) {
            case RANDOM:
                // 随机波动，但确保变化超过阈值
                currentPrice = basePrice + (random.nextDouble() - 0.5) * priceSpread;
                break;
                
            case TREND_UP:
                // 上涨趋势：逐步上涨，每次变化超过阈值
                currentPrice += priceStepPerUpdate * (1.0 + random.nextDouble() * 0.5);
                // 限制在基础价格附近
                if (currentPrice > basePrice + priceSpread) {
                    currentPrice = basePrice + priceSpread * 0.5;
                }
                break;
                
            case TREND_DOWN:
                // 下跌趋势：逐步下跌
                currentPrice -= priceStepPerUpdate * (1.0 + random.nextDouble() * 0.5);
                if (currentPrice < basePrice - priceSpread) {
                    currentPrice = basePrice - priceSpread * 0.5;
                }
                break;
                
            case OSCILLATE:
                // 震荡模式：使用正弦波 + 随机波动
                double oscillation = Math.sin(updateCount * 0.1) * priceSpread * 0.3;
                double noise = (random.nextDouble() - 0.5) * priceSpread * 0.2;
                currentPrice = basePrice + oscillation + noise;
                // 确保有足够的趋势变化
                if (updateCount % 10 == 0) {
                    currentPrice += (random.nextBoolean() ? 1 : -1) * priceStepPerUpdate * 5;
                }
                break;
        }
    }
    
    /**
     * 直接构建并发布 CoreEvent 到队列。
     * <p>
     * 优化点：
     * 1. 确保买一卖一之间有合理的价差（bidAskSpread）
     * 2. 买一价 = midPrice - bidAskSpread/2，卖一价 = midPrice + bidAskSpread/2
     * 3. 生成更真实的订单簿深度（bestBidQty 和 bestAskQty 更合理）
     * 4. 直接将 double 转换为 long（放大 1e8），避免 JSON 解析开销
     */
    private void publishDepthUpdateEvent(double midPrice, long U, long u) {
        if (ringBuffer == null) {
            return; // 如果没有 ringBuffer，直接返回
        }
        
        // 获取序列号
        long seq = ringBuffer.next();
        try {
            CoreEvent event = ringBuffer.get(seq);
            event.reset(); // 确保干净状态
            
            // 设置基本信息
            event.type = CoreEventType.DEPTH_UPDATE;
            event.exchangeId = Exchange.Test.id();
            event.symbolId = symbolId;
            event.accountId = 0; // 公共行情
            
            // 时间戳
            event.timestamp = System.currentTimeMillis();
            event.recvTime = System.nanoTime(); // 接收时间（纳秒精度）
            
            // U 和 u (firstUpdateId 和 sequence)
            event.firstUpdateId = U;
            event.sequence = u; // 使用 sequence 字段存储 u
            
            // 计算买一和卖一价格（确保有合理的价差）
            // 卖一必须永远大于买一，至少相差一个 tickSize
            double bestBidPrice = midPrice - bidAskSpread / 2.0;
            double bestAskPrice = midPrice + bidAskSpread / 2.0;
            
            // 保留两位小数
            bestBidPrice = Math.round(bestBidPrice * 100.0) / 100.0;
            bestAskPrice = Math.round(bestAskPrice * 100.0) / 100.0;
            
            // 确保卖一至少比买一大一个 tickSize
            if (bestAskPrice <= bestBidPrice) {
                bestAskPrice = bestBidPrice + tickSize;
                // 重新保留两位小数
                bestAskPrice = Math.round(bestAskPrice * 100.0) / 100.0;
            }
            
            // 先定义档位数量
            int bidCount = Math.min(depthLevels, CoreEvent.MAX_DEPTH);
            int askCount = Math.min(depthLevels, CoreEvent.MAX_DEPTH);
            
            // 判断价格变化方向
            double priceChange = midPrice - lastPrice;
            boolean priceUp = priceChange > 0.0001; // 价格上涨超过阈值
            boolean priceDown = priceChange < -0.0001; // 价格下跌超过阈值
            
            // 第一步：填充买盘（bids）：从 bestBidPrice 向下（i=0 是最高价，i 越大价格越低）
            for (int i = 0; i < bidCount; i++) {
                double price = bestBidPrice - i * tickSize;
                // 保留两位小数
                price = Math.round(price * 100.0) / 100.0;
                long priceE8 = (long) (price * ScaleConstants.SCALE_E8);
                
                long qtyE8;
                if (hasLastOrderBook) {
                    // 查找上一次相同价格的档位
                    long lastQty = findLastQtyByPrice(priceE8, lastBidPrices, lastBidQtys, lastBidCount);
                    if (lastQty > 0) {
                        // 保持上一次的数量
                        qtyE8 = lastQty;
                    } else {
                        // 新档位：使用指数衰减模型生成数量
                        double depthFactor = Math.exp(-i * 0.2);
                        double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                        qty = Math.round(qty * 100.0) / 100.0;
                        qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                    }
                } else {
                    // 首次生成：使用指数衰减模型
                    double depthFactor = Math.exp(-i * 0.2);
                    double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                    qty = Math.round(qty * 100.0) / 100.0;
                    qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                }
                
                event.bidPrices[i] = priceE8;
                event.bidQtys[i] = qtyE8;
            }
            
            // 第二步：获取当前买一价格（bids[0]），然后找出本地缓存中被吃掉的 asks 档位
            long currentBestBidPriceE8 = event.bidPrices[0];
            
            // 被吃掉的 asks 档位（价格上涨时，本地缓存中价格 < bids[0] 的所有 asks 档位）
            int eatenAskCount = 0;
            long[] eatenAskPrices = new long[CoreEvent.MAX_DEPTH];
            long[] eatenAskQtys = new long[CoreEvent.MAX_DEPTH];
            
            if (hasLastOrderBook && priceUp) {
                // 遍历本地缓存的所有 asks，找出价格 < bids[0] 的档位
                System.err.printf("[EATEN] 价格上涨，检查本地缓存 asks，当前买一价格=%.2f%n",
                    currentBestBidPriceE8 / (double) ScaleConstants.SCALE_E8);
                for (int i = 0; i < lastAskCount; i++) {
                    long lastPriceE8 = lastAskPrices[i];
                    long lastQtyE8 = lastAskQtys[i];
                    // 如果本地缓存中有价格 < 当前买一价格的 asks 档位，都要设置为 0
                    if (lastQtyE8 > 0 && lastPriceE8 < currentBestBidPriceE8) {
                        eatenAskPrices[eatenAskCount] = lastPriceE8;
                        eatenAskQtys[eatenAskCount] = 0;
                        eatenAskCount++;
                        System.err.printf("[EATEN] asks 档位被吃掉: price=%.2f, lastQty=%.2f -> qty=0%n",
                            lastPriceE8 / (double) ScaleConstants.SCALE_E8,
                            lastQtyE8 / (double) ScaleConstants.SCALE_E8);
                    }
                }
                System.err.printf("[EATEN] 总共找到 %d 个被吃掉的 asks 档位%n", eatenAskCount);
            }
            
            // 被吃掉的 bids 档位（价格下跌时，本地缓存中价格 > asks[0] 的所有 bids 档位）
            int eatenBidCount = 0;
            long[] eatenBidPrices = new long[CoreEvent.MAX_DEPTH];
            long[] eatenBidQtys = new long[CoreEvent.MAX_DEPTH];
            
            // 注意：asks 还没有填充，所以先跳过，等 asks 填充后再处理
            
            // 第三步：填充卖盘（asks）：从 bestAskPrice 向上（i=0 是最低价，i 越大价格越高）
            for (int i = 0; i < askCount; i++) {
                double price = bestAskPrice + i * tickSize;
                // 保留两位小数
                price = Math.round(price * 100.0) / 100.0;
                long priceE8 = (long) (price * ScaleConstants.SCALE_E8);
                
                long qtyE8;
                if (hasLastOrderBook) {
                    // 查找上一次相同价格的档位
                    long lastQty = findLastQtyByPrice(priceE8, lastAskPrices, lastAskQtys, lastAskCount);
                    
                    // 如果价格上涨且价格 < 当前买一价格（bids[0]），这个档位被买单吃掉了
                    if (priceUp && priceE8 < currentBestBidPriceE8 && lastQty > 0) {
                        // 被完全吃掉，置为 0
                        qtyE8 = 0;
                        System.err.printf("[EATEN] 当前订单簿 asks 档位被吃掉: price=%.2f, lastQty=%.2f -> qty=0 (price < bids[0]=%.2f)%n",
                            priceE8 / (double) ScaleConstants.SCALE_E8,
                            lastQty / (double) ScaleConstants.SCALE_E8,
                            currentBestBidPriceE8 / (double) ScaleConstants.SCALE_E8);
                    } else if (lastQty > 0) {
                        // 保持上一次的数量
                        qtyE8 = lastQty;
                    } else {
                        // 新档位：使用指数衰减模型生成数量
                        double depthFactor = Math.exp(-i * 0.2);
                        double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                        qty = Math.round(qty * 100.0) / 100.0;
                        qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                    }
                } else {
                    // 首次生成：使用指数衰减模型
                    double depthFactor = Math.exp(-i * 0.2);
                    double qty = baseQty * depthFactor * (1.0 + random.nextDouble() * 0.3);
                    qty = Math.round(qty * 100.0) / 100.0;
                    qtyE8 = (long) (qty * ScaleConstants.SCALE_E8);
                }
                
                event.askPrices[i] = priceE8;
                event.askQtys[i] = qtyE8;
            }
            
            // 获取当前卖一价格（asks[0]）
            long currentBestAskPriceE8 = event.askPrices[0];
            
            // 价格下跌时：找出本地缓存中价格 > asks[0] 的 bids 档位
            if (hasLastOrderBook && priceDown) {
                System.err.printf("[EATEN] 价格下跌，检查本地缓存 bids，当前卖一价格=%.2f%n",
                    currentBestAskPriceE8 / (double) ScaleConstants.SCALE_E8);
                for (int i = 0; i < lastBidCount; i++) {
                    long lastPriceE8 = lastBidPrices[i];
                    long lastQtyE8 = lastBidQtys[i];
                    // 如果本地缓存中有价格 > 当前卖一价格的 bids 档位，都要设置为 0
                    if (lastQtyE8 > 0 && lastPriceE8 > currentBestAskPriceE8) {
                        eatenBidPrices[eatenBidCount] = lastPriceE8;
                        eatenBidQtys[eatenBidCount] = 0;
                        eatenBidCount++;
                        System.err.printf("[EATEN] bids 档位被吃掉: price=%.2f, lastQty=%.2f -> qty=0%n",
                            lastPriceE8 / (double) ScaleConstants.SCALE_E8,
                            lastQtyE8 / (double) ScaleConstants.SCALE_E8);
                    }
                }
                System.err.printf("[EATEN] 总共找到 %d 个被吃掉的 bids 档位%n", eatenBidCount);
            }
            
            // 第四步：将被吃掉的档位追加到当前订单簿更新中（数量为0）
            // 被吃掉的 asks 档位（价格上涨时，本地缓存中价格 < bids[0] 的所有 asks 档位）
            System.err.printf("[APPEND] 准备追加 %d 个被吃掉的 asks 档位%n", eatenAskCount);
            for (int i = 0; i < eatenAskCount && askCount < CoreEvent.MAX_DEPTH; i++) {
                // 检查是否已经存在（避免重复）
                boolean found = false;
                for (int j = 0; j < askCount; j++) {
                    if (Math.abs(event.askPrices[j] - eatenAskPrices[i]) <= (long)(tickSize * ScaleConstants.SCALE_E8)) {
                        found = true;
                        System.err.printf("[APPEND] asks 档位已存在，跳过: price=%.2f%n",
                            eatenAskPrices[i] / (double) ScaleConstants.SCALE_E8);
                        break;
                    }
                }
                if (!found) {
                    event.askPrices[askCount] = eatenAskPrices[i];
                    event.askQtys[askCount] = 0; // 被吃掉，数量为0
                    askCount++;
                    System.err.printf("[APPEND] 追加被吃掉的 asks 档位: price=%.2f, qty=0, askCount=%d%n",
                        eatenAskPrices[i] / (double) ScaleConstants.SCALE_E8, askCount);
                }
            }
            
            // 被吃掉的 bids 档位（价格下跌时，本地缓存中价格 > asks[0] 的所有 bids 档位）
            System.err.printf("[APPEND] 准备追加 %d 个被吃掉的 bids 档位%n", eatenBidCount);
            for (int i = 0; i < eatenBidCount && bidCount < CoreEvent.MAX_DEPTH; i++) {
                // 检查是否已经存在（避免重复）
                boolean found = false;
                for (int j = 0; j < bidCount; j++) {
                    if (Math.abs(event.bidPrices[j] - eatenBidPrices[i]) <= (long)(tickSize * ScaleConstants.SCALE_E8)) {
                        found = true;
                        System.err.printf("[APPEND] bids 档位已存在，跳过: price=%.2f%n",
                            eatenBidPrices[i] / (double) ScaleConstants.SCALE_E8);
                        break;
                    }
                }
                if (!found) {
                    event.bidPrices[bidCount] = eatenBidPrices[i];
                    event.bidQtys[bidCount] = 0; // 被吃掉，数量为0
                    bidCount++;
                    System.err.printf("[APPEND] 追加被吃掉的 bids 档位: price=%.2f, qty=0, bidCount=%d%n",
                        eatenBidPrices[i] / (double) ScaleConstants.SCALE_E8, bidCount);
                }
            }
            
            // 保存当前订单簿状态，供下次使用
            System.arraycopy(event.bidPrices, 0, lastBidPrices, 0, bidCount);
            System.arraycopy(event.bidQtys, 0, lastBidQtys, 0, bidCount);
            System.arraycopy(event.askPrices, 0, lastAskPrices, 0, askCount);
            System.arraycopy(event.askQtys, 0, lastAskQtys, 0, askCount);
            lastBidCount = bidCount;
            lastAskCount = askCount;
            lastPrice = midPrice;
            hasLastOrderBook = true;
            
            // 设置深度档位数量
            event.depthCount = Math.max(bidCount, askCount);
            
            // 使用 Jackson 序列化成 JSON 并打印（只包含 event 中的字段）
            println(event, bidCount, askCount);

        } catch (Exception e) {
            // 发生异常时，设置事件类型为 NONE，消费者会忽略
            CoreEvent event = ringBuffer.get(seq);
            event.type = CoreEventType.NONE;
            System.err.println("生成测试数据失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 必须发布，否则会导致 RingBuffer 阻塞
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

            // bids 数组（只包含有效的档位）
            ArrayNode bidsArray = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < event.depthCount && i < bidCount; i++) {
                ArrayNode level = OBJECT_MAPPER.createArrayNode();
                // 价格：保留两位小数
                double price = event.bidPrices[i] / (double) ScaleConstants.SCALE_E8;
                level.add(Math.round(price * 100.0) / 100.0);
                // 数量：如果为 0，直接输出 0；否则保留两位小数
                double qty = event.bidQtys[i] == 0 ? 0.0 : event.bidQtys[i] / (double) ScaleConstants.SCALE_E8;
                level.add(qty == 0.0 ? 0.0 : Math.round(qty * 100.0) / 100.0);
                bidsArray.add(level);
            }
            json.set("bids", bidsArray);

            // asks 数组（只包含有效的档位）
            ArrayNode asksArray = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < event.depthCount && i < askCount; i++) {
                ArrayNode level = OBJECT_MAPPER.createArrayNode();
                // 价格：保留两位小数
                double price = event.askPrices[i] / (double) ScaleConstants.SCALE_E8;
                level.add(Math.round(price * 100.0) / 100.0);
                // 数量：如果为 0，直接输出 0；否则保留两位小数
                double qty = event.askQtys[i] == 0 ? 0.0 : event.askQtys[i] / (double) ScaleConstants.SCALE_E8;
                level.add(qty == 0.0 ? 0.0 : Math.round(qty * 100.0) / 100.0);
                asksArray.add(level);
            }
            json.set("asks", asksArray);

            System.out.println(OBJECT_MAPPER.writeValueAsString(json));
        } catch (Exception e) {
            // JSON 序列化失败时，使用简单的日志
            System.err.println("JSON 序列化失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据价格查找上一次订单簿中对应档位的数量。
     * 如果找不到完全匹配的价格，返回 0。
     */
    private long findLastQtyByPrice(long targetPriceE8, long[] lastPrices, long[] lastQtys, int count) {
        // 允许价格误差（1个tick）
        long tolerance = (long) (tickSize * ScaleConstants.SCALE_E8);
        for (int i = 0; i < count; i++) {
            if (Math.abs(lastPrices[i] - targetPriceE8) <= tolerance) {
                return lastQtys[i];
            }
        }
        return 0;
    }
}
