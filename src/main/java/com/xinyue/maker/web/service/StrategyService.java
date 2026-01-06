package com.xinyue.maker.web.service;

import com.lmax.disruptor.RingBuffer;
import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.CoreEventType;
import com.xinyue.maker.core.lob.LobManager;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.strategy.InternalRangeOscillatorStrategy2;
import com.xinyue.maker.strategy.MarketMakingStrategy;
import com.xinyue.maker.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 策略管理服务。
 * 负责策略的启动、停止和状态管理。
 */
public final class StrategyService {

    private static final Logger LOG = LoggerFactory.getLogger(StrategyService.class);

    private final StrategyEngine strategyEngine;
    private final OrderManagementSystem oms;
    private final PositionManager positionManager;
    private final LobManager lobManager;
    private final RingBuffer<CoreEvent> ringBuffer;

    // 按 symbolId 管理多个策略
    private final java.util.concurrent.ConcurrentHashMap<Short, StrategyInfo> strategies = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 全局统一事件源：每秒发送一次事件（所有策略共享）
    private ScheduledExecutorService globalTimer;
    private final AtomicBoolean timerRunning = new AtomicBoolean(false);
    
    // 向后兼容：单策略模式（已废弃）
    @Deprecated
    @SuppressWarnings("unused")
    private MarketMakingStrategy currentStrategy;
    @Deprecated
    @SuppressWarnings("unused")
    private boolean isRunning = false;
    
    /**
     * 策略信息（包含策略实例和配置）。
     */
    public static class StrategyInfo {
        public final MarketMakingStrategy strategy;
        public final short symbolId;
        public final StrategyConfig config;
        public final String symbol;  // 交易对符号（如 "H2-USDT"），用于退订订单簿
        public final short[] buyAccountIds;  // 买单账户ID列表，用于退订账户订单
        public final short[] sellAccountIds;  // 卖单账户ID列表，用于退订账户订单
        public final int userId;  // 交易员用户ID

        StrategyInfo(MarketMakingStrategy strategy, short symbolId, StrategyConfig config, int userId) {
            this.strategy = strategy;
            this.symbolId = symbolId;
            this.config = config;
            // 构建交易对符号：baseAssetId-QUOTE_ASSET_ID（quoteAssetId 固定为 USDT）
            this.symbol = config.baseAssetId + "-" + config.quoteAssetId;
            this.buyAccountIds = config.buyAccountIds;
            this.sellAccountIds = config.sellAccountIds;
            this.userId = userId;
        }
    }

    public StrategyService(StrategyEngine strategyEngine,
                          OrderManagementSystem oms,
                          PositionManager positionManager,
                          LobManager lobManager,
                          RingBuffer<CoreEvent> ringBuffer) {
        this.strategyEngine = strategyEngine;
        this.oms = oms;
        this.positionManager = positionManager;
        this.lobManager = lobManager;
        this.ringBuffer = ringBuffer;
    }

    /**
     * 启动策略（按 symbolId 路由）。
     *
     * @param config 策略配置参数
     * @param userId 交易员用户ID
     * @return 启动结果信息
     */
    public synchronized String startStrategy(StrategyConfig config, int userId) {
        short symbolId = config.symbolId;
        
        // 检查是否已存在该 symbolId 的策略
        if (strategies.containsKey(symbolId)) {
            return String.format("策略已在运行中（symbolId=%d），请先停止当前策略", symbolId);
        }

        try {
            // 创建策略实例
            AssetRegistry assetRegistry = AssetRegistry.getInstance();
            
            // 获取下单间隔配置（用于策略内部判断）
            long minIntervalMs = config.minIntervalMs != null ? config.minIntervalMs : 3000L;
            long maxIntervalMs = config.maxIntervalMs != null ? config.maxIntervalMs : 6000L;
            
            // 获取上涨/下跌周期分界点（默认 0.7）
            double progressThreshold = config.progressThreshold != null ? config.progressThreshold : 0.7;
            
            // 获取回调配置参数（使用默认值）
            double minCorrectionIntervalRatio = config.minCorrectionIntervalRatio != null ? config.minCorrectionIntervalRatio : 0.015;
            double maxCorrectionIntervalRatio = config.maxCorrectionIntervalRatio != null ? config.maxCorrectionIntervalRatio : 0.08;
            double minCorrectionAmplitudePercent = config.minCorrectionAmplitudePercent != null ? config.minCorrectionAmplitudePercent : 2.0;
            double maxCorrectionAmplitudePercent = config.maxCorrectionAmplitudePercent != null ? config.maxCorrectionAmplitudePercent : 6.0;
            double minCorrectionDurationRatio = config.minCorrectionDurationRatio != null ? config.minCorrectionDurationRatio : 0.017;
            double maxCorrectionDurationRatio = config.maxCorrectionDurationRatio != null ? config.maxCorrectionDurationRatio : 0.04;
            double convergenceThresholdPercent = config.convergenceThresholdPercent != null ? config.convergenceThresholdPercent : 5.0;
            
            MarketMakingStrategy strategy = new InternalRangeOscillatorStrategy2(
                oms,
                positionManager,
                lobManager,
                config.buyAccountIds,
                config.sellAccountIds,
                config.minPriceE8,
                config.maxPriceE8,
                config.tickSizeE8,
                config.volatilityPercent,
                config.symbolId,
                assetRegistry.get(config.baseAssetId),
                assetRegistry.get(config.quoteAssetId),
                config.exchangeId,
                config.cycleDurationMs,
                config.targetVolumeE8,
                config.triggerIntervalMs,
                config.enableVolumeTarget,
                config.makerCounts,
                config.noiseFactory,
                minIntervalMs,
                maxIntervalMs,
                progressThreshold,
                minCorrectionIntervalRatio,
                maxCorrectionIntervalRatio,
                minCorrectionAmplitudePercent,
                maxCorrectionAmplitudePercent,
                minCorrectionDurationRatio,
                maxCorrectionDurationRatio,
                convergenceThresholdPercent
            );

            // 设置策略到策略引擎（按 symbolId 路由）
            strategyEngine.setMarketMakingStrategy(symbolId, strategy);

            // 启动全局统一事件源（如果未启动）
            startGlobalTimer();

            // 保存策略信息
            strategies.put(symbolId, new StrategyInfo(strategy, symbolId, config, userId));

            String message = String.format("策略启动成功（symbolId=%d）: minPrice=%.2f, maxPrice=%.2f, volatility=%.2f%%",
                    symbolId,
                    config.minPriceE8 / 1_0000_0000.0,
                    config.maxPriceE8 / 1_0000_0000.0,
                    config.volatilityPercent);
            LOG.info(message);
            return message;
        } catch (Exception e) {
            LOG.error("启动策略失败（symbolId={}）", symbolId, e);
            return String.format("启动策略失败（symbolId=%d）: %s", symbolId, e.getMessage());
        }
    }

    /**
     * 停止指定 symbolId 的策略。
     * 
     * @param symbolId 交易对 ID
     * @return 停止结果信息
     */
    public synchronized String stopStrategy(short symbolId) {
        StrategyInfo info = strategies.remove(symbolId);
        if (info == null) {
            return String.format("策略未在运行（symbolId=%d）", symbolId);
        }

        try {
            // 从策略引擎中移除策略
            strategyEngine.removeMarketMakingStrategy(symbolId);
            
            // 如果没有活跃策略了，停止全局定时器
            if (strategies.isEmpty()) {
                stopGlobalTimer();
            }

            LOG.info("策略已停止（symbolId={}）", symbolId);
            return String.format("策略已停止（symbolId=%d）", symbolId);
        } catch (Exception e) {
            LOG.error("停止策略失败（symbolId={}）", symbolId, e);
            return String.format("停止策略失败（symbolId=%d）: %s", symbolId, e.getMessage());
        }
    }
    
    /**
     * 停止所有策略。
     */
    public synchronized String stopAllStrategies() {
        if (strategies.isEmpty()) {
            return "没有运行中的策略";
        }

        try {
            java.util.Set<Short> symbolIds = new java.util.HashSet<>(strategies.keySet());
            for (Short symbolId : symbolIds) {
                stopStrategy(symbolId);
            }

            LOG.info("所有策略已停止");
            return "所有策略已停止";
        } catch (Exception e) {
            LOG.error("停止所有策略失败", e);
            return "停止所有策略失败: " + e.getMessage();
        }
    }

    /**
     * 获取指定 symbolId 的策略状态。
     */
    public StrategyStatus getStatus(short symbolId) {
        StrategyStatus status = new StrategyStatus();
        StrategyInfo info = strategies.get(symbolId);
        if (info != null) {
            status.isRunning = true;
            status.symbolId = symbolId;
            status.strategyType = info.strategy.getClass().getSimpleName();
            status.config = info.config;
            
            // 如果是 InternalRangeOscillatorStrategy2，获取运行状态
            if (info.strategy instanceof com.xinyue.maker.strategy.InternalRangeOscillatorStrategy2) {
                com.xinyue.maker.strategy.InternalRangeOscillatorStrategy2 strategy2 = 
                    (com.xinyue.maker.strategy.InternalRangeOscillatorStrategy2) info.strategy;
                status.runtimeStatus = strategy2.getStatusInfo();
            }
        } else {
            status.isRunning = false;
            status.symbolId = symbolId;
            status.strategyType = null;
            status.config = null;
            status.runtimeStatus = null;
        }
        return status;
    }
    
    /**
     * 获取所有策略的状态。
     */
    public java.util.Map<Short, StrategyStatus> getAllStatus() {
        java.util.Map<Short, StrategyStatus> result = new java.util.HashMap<>();
        for (Short symbolId : strategies.keySet()) {
            result.put(symbolId, getStatus(symbolId));
        }
        return result;
    }
    
    /**
     * 获取指定交易员的所有正在运行的策略状态。
     *
     * @param userId 交易员用户ID
     * @return 策略状态列表
     */
    public java.util.List<StrategyStatus> getStrategiesByUserId(int userId) {
        java.util.List<StrategyStatus> result = new java.util.ArrayList<>();
        for (StrategyInfo info : strategies.values()) {
            if (info.userId == userId) {
                result.add(getStatus(info.symbolId));
            }
        }
        return result;
    }

    /**
     * 获取策略的订阅信息（用于退订）。
     * 
     * @param symbolId 交易对 ID
     * @return 策略信息，如果不存在返回 null
     */
    public StrategyInfo getStrategyInfo(short symbolId) {
        return strategies.get(symbolId);
    }

    /**
     * 策略配置参数。
     */
    public static class StrategyConfig {
        public short[] buyAccountIds;
        public short[] sellAccountIds;
        public long minPriceE8;
        public long maxPriceE8;
        public long tickSizeE8;
        public double volatilityPercent;
        public short symbolId;
        public String baseAssetId;
        public String quoteAssetId;
        public short exchangeId;
        public long cycleDurationMs;
        public long targetVolumeE8;
        public long triggerIntervalMs;
        public boolean enableVolumeTarget;
        public int makerCounts;
        public double noiseFactory;
        public Long minIntervalMs;  // 可选
        public Long maxIntervalMs;  // 可选
        public Double progressThreshold;  // 可选，上涨/下跌周期分界点（0.0 ~ 1.0），默认 0.7
        
        // 回调参数（可选）
        public Double minCorrectionIntervalRatio;  // 最小回调间隔比例，默认 0.015
        public Double maxCorrectionIntervalRatio;  // 最大回调间隔比例，默认 0.08
        public Double minCorrectionAmplitudePercent;  // 最小回调幅度，默认 2.0
        public Double maxCorrectionAmplitudePercent;  // 最大回调幅度，默认 6.0
        public Double minCorrectionDurationRatio;  // 最小回调持续时间比例，默认 0.017
        public Double maxCorrectionDurationRatio;  // 最大回调持续时间比例，默认 0.04
        public Double convergenceThresholdPercent;  // 收敛阈值，默认 5.0
    }

    /**
     * 策略状态。
     */
    public static class StrategyStatus {
        public boolean isRunning;
        public short symbolId;
        public String strategyType;
        
        // 策略配置（从 StrategyConfig 获取）
        public StrategyConfig config;
        
        // 策略运行状态（从策略实例获取，仅 InternalRangeOscillatorStrategy2 支持）
        public Object runtimeStatus;  // InternalRangeOscillatorStrategy2.StrategyStatusInfo
    }
    
    /**
     * 启动全局统一事件源（每秒发送一次事件）。
     */
    private synchronized void startGlobalTimer() {
        if (timerRunning.compareAndSet(false, true)) {
            globalTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "global-strategy-timer");
                t.setDaemon(true);
                return t;
            });
            
            // 每秒发送一次事件（symbolId=0，表示全局事件）
            globalTimer.scheduleAtFixedRate(() -> {
                try {
                    long sequence = ringBuffer.next();
                    try {
                        CoreEvent event = ringBuffer.get(sequence);
                        event.reset();
                        event.type = CoreEventType.TEST;
                        event.timestamp = System.currentTimeMillis();
                        event.recvTime = System.nanoTime();
                        event.symbolId = 0; // symbolId=0 表示全局事件，所有策略都会收到
                    } finally {
                        ringBuffer.publish(sequence);
                    }
                } catch (Exception e) {
                    LOG.error("全局定时器发送事件失败", e);
                }
            }, 0, 1, TimeUnit.SECONDS);
            
            LOG.info("全局统一事件源已启动（每秒发送一次事件）");
        }
    }
    
    /**
     * 停止全局统一事件源。
     */
    private synchronized void stopGlobalTimer() {
        if (timerRunning.compareAndSet(true, false)) {
            if (globalTimer != null) {
                globalTimer.shutdown();
                try {
                    if (!globalTimer.awaitTermination(2, TimeUnit.SECONDS)) {
                        globalTimer.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    globalTimer.shutdownNow();
                }
                globalTimer = null;
            }
            LOG.info("全局统一事件源已停止");
        }
    }
}

