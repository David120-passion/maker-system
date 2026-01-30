package com.xinyue.maker.strategy;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.OrderCommand;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.core.lob.ILocalOrderBook;
import com.xinyue.maker.core.lob.LobManager;
import com.xinyue.maker.core.lob.OrderBookSnapshot;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内部区间震荡策略（Internal Range Oscillator Strategy）。
 * <p>
 * 策略职责：
 * - 针对 dYdX 的指定范围刷量策略
 * - 在指定的封闭价格区间内生成价格波动并产生交易量
 * <p>
 * 核心架构："先铺路（Maker），再走路（Taker）"
 * - Maker：在目标价格上下挂单，提供流动性
 * - Taker：吃掉自己挂的 Maker 单，推动价格并产生成交量
 */
public final class InternalRangeOscillatorStrategy2 implements MarketMakingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(InternalRangeOscillatorStrategy2.class);

    // === 配置参数（final，运行时不可变）===
    private final long minPriceE8;           // 最低价（放大 10^8 倍），默认 $95,000
    private final long maxPriceE8;           // 最高价（放大 10^8 倍），默认 $105,000
    private final long tickSizeE8;           // 最小变动单位（放大 10^8 倍）
    private final double volatilityPercent;  // 波动率百分比（例如 0.5 表示 0.5%），相对于价格区间中点的标准差
    @SuppressWarnings("unused")
    // 新增配置参数
    private final long cycleDurationMs;      // 周期时长（毫秒），默认 6 小时 = 21600000 ms
    private final long targetVolumeE8;       // 目标量（放大 10^8 倍），5000 BTC 或 100万 USDT
    private final boolean enableVolumeTarget; // 是否启用目标量控制

    // 订单执行参数
    private final short symbolId;            // 交易对 ID
    private final short exchangeId;          // 交易所 ID（DYDX）
    private final short baseAssetId;         // 基础资产 ID（如 ETH）
    private final short quoteAssetId;        // 报价资产 ID（如 USDT）

    // 订单数量范围（动态计算，根据周期时长和目标量自动计算）
    private final long minOrderQtyE8;        // 最小单笔数量（放大 10^8 倍），根据周期和目标量动态计算
    private final long maxOrderQtyE8;        // 最大单笔数量（放大 10^8 倍），根据周期和目标量动态计算

    private final long minKeepOrderQtyE8 = 1000000;        // 最小单笔数量（放大 10^8 倍），根据周期和目标量动态计算

    // 账户分工配置：Maker/Taker 分离
    private final short[] buyAccountIds;     // 买入账户组：挂买单（Maker），吃卖单（Taker）
    private final short[] sellAccountIds;    // 卖出账户组：挂卖单（Maker），吃买单（Taker）

    // === 依赖组件 ===
    private final OrderManagementSystem oms;
    private final PositionManager positionManager;
    private final LobManager lobManager;

    // === 状态变量（运行时可变）===
    private double currentTargetPriceE8;     // 当前策略计算出的目标价格（用 double 保持计算精度）
    @SuppressWarnings("unused")
    private boolean isRising = true;         // 当前是否处于上涨状态（true=上涨，false=下跌），用于日志和状态跟踪

    // 周期和成交量统计
    private long cycleStartTime;             // 周期开始时间（毫秒时间戳）
    private long accumulatedVolumeE8;        // 累计成交量（放大 10^8 倍）
    private final java.util.Random random;   // 随机数生成器（用于生成随机订单数量）

    // 回调状态管理
    private boolean inCorrection = false;            // 是否处于回调中
    private long correctionStartTimeMs;              // 回调开始时间（毫秒）
    private long correctionDurationMs;               // 回调持续时间（毫秒）
    private double correctionAmplitudePercent;       // 回调幅度（百分比，例如 5.0 表示 5%）
    private boolean correctionIsUpward;              // 回调方向（true=向上回调，false=向下回调）
    private long lastCorrectionEndTimeMs;            // 上一次回调结束时间（用于计算回调间隔）

    // 回调配置参数（基于周期时长的百分比，动态调整）
    private final double minCorrectionIntervalRatio;            // 最小回调间隔：周期时长的百分比
    private final double maxCorrectionIntervalRatio;             // 最大回调间隔：周期时长的百分比
    private final double minCorrectionAmplitudePercent;           // 最小回调幅度：百分比
    private final double maxCorrectionAmplitudePercent;           // 最大回调幅度：百分比
    private final double minCorrectionDurationRatio;            // 最小回调持续时间：周期时长的百分比
    private final double maxCorrectionDurationRatio;             // 最大回调持续时间：周期时长的百分比
    private final double convergenceThresholdPercent;              // 收敛阈值：百分比（如果进度落后超过此值，加速）

    private final double progressThreshold;  // 上涨/下跌周期分界点（0.0 ~ 1.0），默认 0.7

    private  int makerCounts;

    // 是否已初始化（首次 onTimer 时初始化价格到区间最小值）
    private boolean initialized = false;

    private  double noiseFactor;// 噪声因子：30% 原始波动率
    
    // 下单间隔配置（策略内部判断是否需要下单）
    private final long minOrderIntervalMs;  // 最小下单间隔（毫秒）
    private final long maxOrderIntervalMs;  // 最大下单间隔（毫秒）
    private long lastOrderTimeMs;           // 上次下单时间（毫秒）
    private long nextOrderIntervalMs;       // 下次下单间隔（毫秒，随机生成）

    /**
     * 构造器。
     * <p>
     * 账户分工策略（Maker/Taker 分离）：
     * - buyAccountIds：买入账户组，负责挂买单（Maker）和吃卖单（Taker）
     * - sellAccountIds：卖出账户组，负责挂卖单（Maker）和吃买单（Taker）
     *
     * @param oms 订单管理系统
     * @param positionManager 仓位管理器（用于检查账户余额）
     * @param lobManager 订单簿管理器（用于读取 dYdX 订单簿）
     * @param buyAccountIds 买入账户组 ID 列表（不能为空）
     * @param sellAccountIds 卖出账户组 ID 列表（不能为空）
     * @param minPriceE8 最低价（放大 10^8 倍），默认 $95,000
     * @param maxPriceE8 最高价（放大 10^8 倍），默认 $105,000
     * @param tickSizeE8 最小变动单位（放大 10^8 倍）
     * @param volatilityPercent 波动率百分比（例如 0.5 表示 0.5%），相对于价格区间中点的标准差
     * @param symbolId 交易对 ID
     * @param baseAssetId 基础资产 ID（如 ETH）
     * @param quoteAssetId 报价资产 ID（如 USDT）
     * @param exchangeId 交易所 ID
     * @param cycleDurationMs 周期时长（毫秒），默认 6 小时 = 21600000 ms
     * @param targetVolumeE8 目标量（放大 10^8 倍），5000 BTC 或 100万 USDT
     * @param triggerIntervalMs 策略触发间隔（毫秒），用于计算订单数量范围，默认 6000ms（6秒）
     * @param enableVolumeTarget 是否启用目标量控制
     * @param minOrderIntervalMs 最小下单间隔（毫秒），策略内部判断是否需要下单
     * @param maxOrderIntervalMs 最大下单间隔（毫秒），策略内部判断是否需要下单
     * @param progressThreshold 上涨/下跌周期分界点（0.0 ~ 1.0），默认 0.7。小于此值为上涨周期，大于等于此值为下跌周期
     * @param minCorrectionIntervalRatio 最小回调间隔：周期时长的百分比，默认 0.015（1.5%）
     * @param maxCorrectionIntervalRatio 最大回调间隔：周期时长的百分比，默认 0.08（8%）
     * @param minCorrectionAmplitudePercent 最小回调幅度：百分比，默认 2.0
     * @param maxCorrectionAmplitudePercent 最大回调幅度：百分比，默认 6.0
     * @param minCorrectionDurationRatio 最小回调持续时间：周期时长的百分比，默认 0.017（1.7%）
     * @param maxCorrectionDurationRatio 最大回调持续时间：周期时长的百分比，默认 0.04（4%）
     * @param convergenceThresholdPercent 收敛阈值：百分比，默认 5.0（如果进度落后超过此值，加速）
     */
    public InternalRangeOscillatorStrategy2(
            OrderManagementSystem oms,
            PositionManager positionManager,
            LobManager lobManager,
            short[] buyAccountIds,
            short[] sellAccountIds,
            long minPriceE8,
            long maxPriceE8,
            long tickSizeE8,
            double volatilityPercent,
            short symbolId,
            short baseAssetId,
            short quoteAssetId,
            short exchangeId,
            long cycleDurationMs,
            long targetVolumeE8,
            long triggerIntervalMs,
            boolean enableVolumeTarget,
            int makerCounts,
            double noiseFactor,
            long minOrderIntervalMs,
            long maxOrderIntervalMs,
            double progressThreshold,
            double minCorrectionIntervalRatio,
            double maxCorrectionIntervalRatio,
            double minCorrectionAmplitudePercent,
            double maxCorrectionAmplitudePercent,
            double minCorrectionDurationRatio,
            double maxCorrectionDurationRatio,
            double convergenceThresholdPercent) {
        
        // 参数校验
        if (minPriceE8 >= maxPriceE8) {
            throw new IllegalArgumentException("minPriceE8 必须小于 maxPriceE8");
        }
        if (tickSizeE8 <= 0) {
            throw new IllegalArgumentException("tickSizeE8 必须大于 0");
        }
        if (volatilityPercent <= 0 || volatilityPercent > 10.0) {
            throw new IllegalArgumentException("volatilityPercent 必须在 0.0~10.0 之间（建议 0.1~1.0）");
        }
        if (buyAccountIds == null || buyAccountIds.length == 0) {
            throw new IllegalArgumentException("buyAccountIds 不能为空");
        }
        if (sellAccountIds == null || sellAccountIds.length == 0) {
            throw new IllegalArgumentException("sellAccountIds 不能为空");
        }
        if (cycleDurationMs <= 0) {
            throw new IllegalArgumentException("cycleDurationMs 必须大于 0");
        }
        if (targetVolumeE8 <= 0) {
            throw new IllegalArgumentException("targetVolumeE8 必须大于 0");
        }
        if (triggerIntervalMs <= 0) {
            throw new IllegalArgumentException("triggerIntervalMs 必须大于 0");
        }
        if(makerCounts < 5){
            throw new IllegalArgumentException("至少铺五档深度");
        }
        if (minOrderIntervalMs <= 0 || maxOrderIntervalMs <= 0) {
            throw new IllegalArgumentException("minOrderIntervalMs 和 maxOrderIntervalMs 必须大于 0");
        }
        if (minOrderIntervalMs > maxOrderIntervalMs) {
            throw new IllegalArgumentException("minOrderIntervalMs 不能大于 maxOrderIntervalMs");
        }
        if (progressThreshold <= 0.0 || progressThreshold >= 1.0) {
            throw new IllegalArgumentException("progressThreshold 必须在 0.0~1.0 之间");
        }
        if (minCorrectionIntervalRatio < 0 || maxCorrectionIntervalRatio < 0 || minCorrectionIntervalRatio > maxCorrectionIntervalRatio) {
            throw new IllegalArgumentException("回调间隔比例参数无效");
        }
        if (minCorrectionAmplitudePercent < 0 || maxCorrectionAmplitudePercent < 0 || minCorrectionAmplitudePercent > maxCorrectionAmplitudePercent) {
            throw new IllegalArgumentException("回调幅度参数无效");
        }
        if (minCorrectionDurationRatio < 0 || maxCorrectionDurationRatio < 0 || minCorrectionDurationRatio > maxCorrectionDurationRatio) {
            throw new IllegalArgumentException("回调持续时间比例参数无效");
        }
        if (convergenceThresholdPercent < 0) {
            throw new IllegalArgumentException("convergenceThresholdPercent 必须大于等于 0");
        }
        
        this.oms = oms;
        this.positionManager = positionManager;
        this.lobManager = lobManager;
        this.buyAccountIds = buyAccountIds.clone();
        this.sellAccountIds = sellAccountIds.clone();
        this.minPriceE8 = minPriceE8;
        this.maxPriceE8 = maxPriceE8;
        this.tickSizeE8 = tickSizeE8;
        this.volatilityPercent = volatilityPercent;
        this.symbolId = symbolId;
        this.baseAssetId = baseAssetId;
        this.quoteAssetId = quoteAssetId;
        this.exchangeId = exchangeId;
        this.cycleDurationMs = cycleDurationMs;
        this.targetVolumeE8 = targetVolumeE8;
        this.enableVolumeTarget = enableVolumeTarget;
        this.random = new java.util.Random();
        this.makerCounts = makerCounts;
        this.noiseFactor = noiseFactor;
        this.minOrderIntervalMs = minOrderIntervalMs;
        this.maxOrderIntervalMs = maxOrderIntervalMs;
        this.progressThreshold = progressThreshold;
        this.minCorrectionIntervalRatio = minCorrectionIntervalRatio;
        this.maxCorrectionIntervalRatio = maxCorrectionIntervalRatio;
        this.minCorrectionAmplitudePercent = minCorrectionAmplitudePercent;
        this.maxCorrectionAmplitudePercent = maxCorrectionAmplitudePercent;
        this.minCorrectionDurationRatio = minCorrectionDurationRatio;
        this.maxCorrectionDurationRatio = maxCorrectionDurationRatio;
        this.convergenceThresholdPercent = convergenceThresholdPercent;
        this.lastOrderTimeMs = 0; // 初始化为0，首次下单时更新
        // 生成首次下单间隔（随机）
        this.nextOrderIntervalMs = minOrderIntervalMs + 
            (long) (random.nextDouble() * (maxOrderIntervalMs - minOrderIntervalMs));
        
        // 根据周期时长和目标量动态计算订单数量范围
        // 计算逻辑：
        // 1. 总触发次数 = 周期时长 / 触发间隔
        // 2. 平均每次触发产生的成交量 = 目标量 / 总触发次数
        // 3. 假设每次触发平均产生 1.5 个成交订单（Maker + Taker）
        // 4. 平均单笔订单量 = 平均每次成交量 / 1.5
        // 5. 最小单笔 = 平均单笔 × 0.2（20%），最大单笔 = 平均单笔 × 2.5（250%）
        long totalTriggers = cycleDurationMs / triggerIntervalMs;
        if (totalTriggers <= 0) {
            totalTriggers = 1; // 防止除零
        }
        double avgVolumePerTriggerE8 = (double) targetVolumeE8 / totalTriggers;
        double avgOrdersPerTrigger = 1;
        double avgOrderQtyE8 = avgVolumePerTriggerE8 / avgOrdersPerTrigger /makerCounts;
        
        // 设置订单数量范围（最小20%，最大250%）
        long calculatedMinOrderQtyE8 = Math.max(minKeepOrderQtyE8, (long) (avgOrderQtyE8 * 0.2)); // 至少为 1（最小单位）
        long calculatedMaxOrderQtyE8 = Math.max(calculatedMinOrderQtyE8 + minKeepOrderQtyE8, (long) (avgOrderQtyE8 * 2.5));
        
        // 确保最小值和最大值合理
        if (calculatedMinOrderQtyE8 >= calculatedMaxOrderQtyE8) {
            calculatedMaxOrderQtyE8 = calculatedMinOrderQtyE8 + Math.max(minKeepOrderQtyE8, (long) (avgOrderQtyE8 * 0.5));
        }
        
        this.minOrderQtyE8 = calculatedMinOrderQtyE8;
        this.maxOrderQtyE8 = calculatedMaxOrderQtyE8;
        
        // 计算价格区间中点，用于显示实际波动幅度
        double midPriceE8 = (minPriceE8 + maxPriceE8) / 2.0;
        double actualVolatilityE8 = midPriceE8 * volatilityPercent / 100.0;
        
        LOG.info("InternalRangeOscillatorStrategy 已创建: symbolId={}, minPrice={}, maxPrice={}, tickSize={}, volatilityPercent={}% (实际波动≈{}), " +
                "cycleDuration={}h, targetVolume={} BTC, triggerInterval={}s, " +
                "orderQtyRange=[{}, {}] BTC (动态计算: 平均单笔={} BTC, 总触发次数={}), " +
                "baseAsset={}, quoteAsset={}, buyAccounts={}, sellAccounts={}, " +
                "progressThreshold={}, minOrderInterval={}ms, maxOrderInterval={}ms, " +
                "correctionIntervalRatio=[{}, {}], correctionAmplitudePercent=[{}, {}], " +
                "correctionDurationRatio=[{}, {}], convergenceThresholdPercent={}%",
            symbolId,
            minPriceE8 / (double) ScaleConstants.SCALE_E8,
            maxPriceE8 / (double) ScaleConstants.SCALE_E8,
            tickSizeE8 / (double) ScaleConstants.SCALE_E8,
            volatilityPercent,
            actualVolatilityE8 / ScaleConstants.SCALE_E8,
            cycleDurationMs / (3600.0 * 1000.0),
            targetVolumeE8 / (double) ScaleConstants.SCALE_E8,
            triggerIntervalMs / 1000.0,
            minOrderQtyE8 / (double) ScaleConstants.SCALE_E8,
            maxOrderQtyE8 / (double) ScaleConstants.SCALE_E8,
            avgOrderQtyE8 / ScaleConstants.SCALE_E8,
            totalTriggers,
            baseAssetId,
            quoteAssetId,
            java.util.Arrays.toString(buyAccountIds),
            java.util.Arrays.toString(sellAccountIds),
            progressThreshold,
            minOrderIntervalMs,
            maxOrderIntervalMs,
            minCorrectionIntervalRatio,
            maxCorrectionIntervalRatio,
            minCorrectionAmplitudePercent,
            maxCorrectionAmplitudePercent,
            minCorrectionDurationRatio,
            maxCorrectionDurationRatio,
            convergenceThresholdPercent);
    }

    @Override
    public void onDepthUpdate(CoreEvent event, OrderBookSnapshot referenceSnapshot) {
        // 本策略不参考外部行情，忽略深度更新
    }

    @Override
    public void onAccountOrderUpdate(CoreEvent event) {
        // 记录关键事件（如订单被拒绝）
        if (event.orderStatus == 7) { // Rejected
            LOG.warn("策略订单被拒绝: symbolId={}, orderId={}, side={}, price={}, qty={}",
                symbolId,
                event.localOrderId,
                event.side == 0 ? "BUY" : "SELL",
                event.price / (double) ScaleConstants.SCALE_E8,
                event.quantity / (double) ScaleConstants.SCALE_E8);
        }
        
        // 统计成交量（订单成交时） 只统计一边
        if ((event.orderStatus == 2 || event.orderStatus == 3 || event.orderStatus == 5 ) && event.side ==0) { // Filled 或 PartiallyFilled
            if (event.totalFillQty > 0 && event.symbolId == symbolId) {
                accumulatedVolumeE8 += event.totalFillQty;
                if (enableVolumeTarget && accumulatedVolumeE8 >= targetVolumeE8) {
                    LOG.info("已达到目标成交量: symbolId={}, {} BTC (目标: {} BTC)",
                        symbolId,
                        accumulatedVolumeE8 / (double) ScaleConstants.SCALE_E8,
                        targetVolumeE8 / (double) ScaleConstants.SCALE_E8);
                }
//                LOG.info("当前总成交量: symbolId={}, {}", symbolId, accumulatedVolumeE8);
            }
        }
    }

    @Override
    public void onTimer(CoreEvent event) {
//        LOG.info("收到事件: symbolId={}", symbolId);
        // 1. 首次初始化
        if (!initialized) {
            currentTargetPriceE8 = minPriceE8;
            isRising = true;
            cycleStartTime = event.timestamp; // 使用事件时间戳作为周期开始时间
            accumulatedVolumeE8 = 0;
            inCorrection = false;
            lastCorrectionEndTimeMs = event.timestamp;
            lastOrderTimeMs = event.timestamp; // 初始化下单时间
            initialized = true;
            LOG.info("策略已初始化: symbolId={}, 起始价格: {}（从最小值开始上涨），周期时长: {} 小时，目标量: {} BTC，下单间隔: {}ms - {}ms",
                symbolId,
                currentTargetPriceE8 / ScaleConstants.SCALE_E8,
                cycleDurationMs / (3600.0 * 1000.0),
                targetVolumeE8 / (double) ScaleConstants.SCALE_E8,
                minOrderIntervalMs,
                maxOrderIntervalMs);
            
            // 首次初始化时，挂5笔大单
            placeInitialLargeOrders(event.timestamp);
            return;
        }else {
//            ILocalOrderBook orderBook = lobManager.getOrderBook(Exchange.DYDX, symbolId);
//            LOG.info("bestAsk:{},bestBid:{}",orderBook.bestAskE8(),orderBook.bestBidE8());
        }
        
        // 检查是否达到目标量（如果启用）
        if (enableVolumeTarget && accumulatedVolumeE8 >= targetVolumeE8) {
            LOG.debug("已达到目标成交量，暂停交易: symbolId={}", symbolId);
            return;
        }
        
        // 2. 策略内部判断：检查是否需要下单（基于配置的下单间隔）
        long elapsedMs = event.timestamp - lastOrderTimeMs;
        if (elapsedMs < nextOrderIntervalMs) {
            // 还没到下单时间，跳过本次事件
            return;
        }
        
        // 3. 计算新的目标价格（基于时间进度）
        double oldPriceE8 = currentTargetPriceE8;
        double newPriceE8 = calculateNewTargetPrice(event.timestamp);
        
        // 4. 执行刷量逻辑："先铺路（Maker），再走路（Taker）"
        executeVolumeStrategy(oldPriceE8, newPriceE8, event.timestamp);
        
        // 5. 更新状态
        currentTargetPriceE8 = newPriceE8;
        lastOrderTimeMs = event.timestamp; // 更新上次下单时间
        // 生成下次下单间隔（随机）
        nextOrderIntervalMs = minOrderIntervalMs + 
            (long) (random.nextDouble() * (maxOrderIntervalMs - minOrderIntervalMs));
//        LOG.info("下次执行时间: symbolId={}, {}秒", symbolId, nextOrderIntervalMs / 1000.0);
    }

    /**
     * 计算新的目标价格（基于时间进度的S曲线震荡上涨/下跌模式，带随机回调和收敛保证）。
     * <p>
     * 算法：
     * - 前 50% 周期：使用S曲线从 minPrice 平滑上涨到 maxPrice，带随机下跌回调
     * - 后 50% 周期：使用S曲线从 maxPrice 平滑下跌到 minPrice，带随机上涨回调
     * - 收敛保证：如果进度落后超过阈值，动态加速
     * - 随机噪声：添加小幅随机波动（30% 原始波动率）
     * 
     * @param currentTimeMs 当前时间（毫秒时间戳）
     * @return 新的目标价格（放大 10^8 倍）
     */
    private double calculateNewTargetPrice(long currentTimeMs) {
        // 计算时间进度（0.0 ~ 1.0）
        long elapsedMs = currentTimeMs - cycleStartTime;
        double progress = Math.min(1.0, (double) elapsedMs / cycleDurationMs);
        
        // 价格区间范围
        double priceRangeE8 = maxPriceE8 - minPriceE8;

        // 1. 计算基础趋势（使用S曲线）
        double baseTrendE8 = calculateBaseTrendWithSigmoid(progress, priceRangeE8);
        
        // 2. 处理回调（检查是否需要开始新回调，或计算当前回调效果）
        updateCorrectionState(currentTimeMs, progress, baseTrendE8, priceRangeE8);
        double correctionEffectE8 = calculateCorrectionEffect(currentTimeMs, baseTrendE8, priceRangeE8);
        
        // 3. 收敛保证：如果进度落后，动态加速
        double convergenceAdjustmentE8 = calculateConvergenceAdjustment(progress, baseTrendE8, priceRangeE8);
        
        // 4. 随机噪声（30% 原始波动率）
        double midPriceE8 = (minPriceE8 + maxPriceE8) / 2.0;
        double baseVolatilityE8 = midPriceE8 * volatilityPercent / 100.0;
        double noiseE8 = random.nextGaussian() * baseVolatilityE8 * noiseFactor;
        
        // 5. 组合所有效果
        double newPriceE8 = baseTrendE8 + correctionEffectE8 + convergenceAdjustmentE8 + noiseE8;
//        LOG.info("baseTrend:{},correctionEffectE8:{},baseVolatilityE8:{},noiseE8:{}",baseTrendE8,correctionEffectE8,baseVolatilityE8,noiseE8);
        
        // 6. 价格对齐到 tickSize 和边界限制
        newPriceE8 = Math.round(newPriceE8 / tickSizeE8) * tickSizeE8;
        newPriceE8 = Math.max(minPriceE8, Math.min(maxPriceE8, newPriceE8));
        
        // 7. 更新方向状态
        if (newPriceE8 > currentTargetPriceE8) {
            isRising = true;
        } else if (newPriceE8 < currentTargetPriceE8) {
            isRising = false;
        }
        
        return newPriceE8;
    }
    
    /**
     * 使用S曲线（Sigmoid）计算基础趋势价格。
     * <p>
     * 前 50% 周期：从 minPrice 平滑上涨到 maxPrice
     * 后 50% 周期：从 maxPrice 平滑下跌到 minPrice
     * 
     * @param progress 时间进度（0.0 ~ 1.0）
     * @param priceRangeE8 价格区间范围（放大 10^8 倍）
     * @return 基础趋势价格（放大 10^8 倍）
     */
    private double calculateBaseTrendWithSigmoid(double progress, double priceRangeE8) {
        if (progress < progressThreshold) {
            // 上涨周期：将 progress 从 [0, progressThreshold] 映射到 [0, 1]
            double upProgress = progress / progressThreshold; // 0.0 ~ 1.0
            // 使用Sigmoid函数：sigmoid(x) = 1 / (1 + e^(-k*(x-0.5)))
            // 调整k值控制S曲线的陡峭程度（k越大越陡）
            double k = 6.0; // 控制S曲线形状，可以调整
            double sigmoidInput = (upProgress - 0.5) * k;
            double sigmoidValue = 1.0 / (1.0 + Math.exp(-sigmoidInput));
            // Sigmoid输出是 0~1，映射到价格区间
            return minPriceE8 + sigmoidValue * priceRangeE8;
        } else {
            // 下跌周期：将 progress 从 [progressThreshold, 1.0] 映射到 [0, 1]
            double downProgress = (progress - progressThreshold) / (1.0 - progressThreshold); // 0.0 ~ 1.0
            // 使用反向Sigmoid：从1平滑下降到0
            double k = 6.0;
            double sigmoidInput = (downProgress - 0.5) * k;
            double sigmoidValue = 1.0 / (1.0 + Math.exp(-sigmoidInput));
            // 反向：从maxPrice平滑下降到minPrice
            return maxPriceE8 - sigmoidValue * priceRangeE8;
        }
    }
    
    /**
     * 更新回调状态（检查是否需要开始新回调）。
     * <p>
     * 回调间隔和持续时间根据周期时长动态计算，确保不同周期长度下的回调频率和持续时间成比例。
     * 
     * @param currentTimeMs 当前时间（毫秒）
     * @param progress 时间进度（0.0 ~ 1.0）
     * @param baseTrendE8 当前基础趋势价格
     * @param priceRangeE8 价格区间范围
     */
    private void updateCorrectionState(long currentTimeMs, double progress, double baseTrendE8, double priceRangeE8) {
        // 如果正在回调中，检查是否结束
        if (inCorrection) {
            long correctionElapsedMs = currentTimeMs - correctionStartTimeMs;
            if (correctionElapsedMs >= correctionDurationMs) {
                // 回调结束
                inCorrection = false;
                lastCorrectionEndTimeMs = currentTimeMs;
            }
            return;
        }
        
        // 根据周期时长动态计算回调间隔和持续时间
        long minCorrectionIntervalMs = (long) (cycleDurationMs * minCorrectionIntervalRatio);
        long maxCorrectionIntervalMs = (long) (cycleDurationMs * maxCorrectionIntervalRatio);
        long minCorrectionDurationMs = (long) (cycleDurationMs * minCorrectionDurationRatio);
        long maxCorrectionDurationMs = (long) (cycleDurationMs * maxCorrectionDurationRatio);
        
        // 检查是否可以开始新回调
        long timeSinceLastCorrection = currentTimeMs - lastCorrectionEndTimeMs;
        if (timeSinceLastCorrection < minCorrectionIntervalMs) {
            return; // 距离上次回调太近，不开始新回调
        }
        
        // 随机决定是否开始回调（基于动态计算的间隔）
        long targetInterval = minCorrectionIntervalMs + 
            (long) (random.nextDouble() * (maxCorrectionIntervalMs - minCorrectionIntervalMs));
        if (timeSinceLastCorrection < targetInterval) {
            return; // 还没到随机间隔时间
        }
        
        // 确定回调方向：上涨周期时回调向下，下跌周期时回调向上
        boolean isUpwardPhase = progress < progressThreshold;
        correctionIsUpward = !isUpwardPhase; // 与主趋势相反
        
        // 随机生成回调参数（使用动态计算的持续时间范围）
        correctionAmplitudePercent = minCorrectionAmplitudePercent + 
            random.nextDouble() * (maxCorrectionAmplitudePercent - minCorrectionAmplitudePercent);
        correctionDurationMs = minCorrectionDurationMs + 
            (long) (random.nextDouble() * (maxCorrectionDurationMs - minCorrectionDurationMs));
        correctionStartTimeMs = currentTimeMs;
        inCorrection = true;
        
        LOG.debug("开始回调: symbolId={}, 方向={}, 幅度={}%, 持续时间={}分钟 (周期{}%), 当前进度={}%",
            symbolId,
            correctionIsUpward ? "向上" : "向下",
            correctionAmplitudePercent,
            correctionDurationMs / (60.0 * 1000.0),
            (correctionDurationMs * 100.0 / cycleDurationMs),
            progress * 100.0);
    }
    
    /**
     * 计算回调效果。
     * <p>
     * 回调曲线：前半段快速回调，后半段缓慢恢复
     * 使用二次函数实现：前半段快速，后半段慢速
     * 
     * @param currentTimeMs 当前时间（毫秒）
     * @param baseTrendE8 基础趋势价格
     * @param priceRangeE8 价格区间范围
     * @return 回调效果（放大 10^8 倍），正值表示向上回调，负值表示向下回调
     */
    private double calculateCorrectionEffect(long currentTimeMs, double baseTrendE8, double priceRangeE8) {
        if (!inCorrection) {
            return 0.0;
        }
        
        long correctionElapsedMs = currentTimeMs - correctionStartTimeMs;
        double correctionProgress = Math.min(1.0, (double) correctionElapsedMs / correctionDurationMs);
        
        // 回调幅度（相对于基础价格的百分比）
        double correctionAmplitudeE8 = baseTrendE8 * correctionAmplitudePercent / 100.0;
        
        // 回调曲线：使用二次函数，前半段快速回调，后半段缓慢恢复
        // y = 4x(1-x)，在x=0.5时达到最大值1，前后对称
        double curveFactor = 4.0 * correctionProgress * (1.0 - correctionProgress);
        
        // 根据回调方向应用效果
        double effectE8;
        if (correctionIsUpward) {
            // 向上回调：增加价格
            effectE8 = correctionAmplitudeE8 * curveFactor;
        } else {
            // 向下回调：减少价格
            effectE8 = -correctionAmplitudeE8 * curveFactor;
        }
        
        return effectE8;
    }
    
    /**
     * 计算收敛调整（如果进度落后，动态加速）。
     * <p>
     * 如果实际价格与目标价格的差距超过阈值，增加调整量以确保按时到达目标价格。
     * 
     * @param progress 时间进度（0.0 ~ 1.0）
     * @param baseTrendE8 当前基础趋势价格（使用Sigmoid计算的价格）
     * @param priceRangeE8 价格区间范围
     * @return 收敛调整量（放大 10^8 倍）
     */
    private double calculateConvergenceAdjustment(double progress, double baseTrendE8, double priceRangeE8) {
        // 计算理想目标价格（基于进度，不考虑回调）
        // 使用线性插值作为理想参考线，与实际Sigmoid曲线对比
        double idealTargetPriceE8;
        if (progress < progressThreshold) {
            // 上涨周期：理想情况下应该在 progress=progressThreshold 时到达 maxPrice
            // 线性映射：progress 从 [0, progressThreshold] 映射到价格 [minPriceE8, maxPriceE8]
            idealTargetPriceE8 = minPriceE8 + (progress / progressThreshold) * priceRangeE8;
        } else {
            // 下跌周期：理想情况下应该在 progress=1.0 时到达 minPrice
            // 线性映射：progress 从 [progressThreshold, 1.0] 映射到价格 [maxPriceE8, minPriceE8]
            double downProgress = (progress - progressThreshold) / (1.0 - progressThreshold); // 0.0 ~ 1.0
            idealTargetPriceE8 = maxPriceE8 - downProgress * priceRangeE8;
        }
        
        // 计算当前基础趋势价格与理想目标价格的差距
        double priceGapE8 = idealTargetPriceE8 - baseTrendE8;
        double gapPercent = Math.abs(priceGapE8) / priceRangeE8 * 100.0;
        
        // 如果差距超过阈值，应用加速调整
        // 注意：Sigmoid在两端（progress接近0或1）会比较慢，在中间会比较快
        // 所以需要特别关注周期分界点（progress=progressThreshold）和结束点（progress=1.0）
        boolean needsAcceleration = false;
        if (progress < progressThreshold) {
            // 上涨周期：在接近分界点（progressThreshold）时需要确保到达 maxPrice
            // 如果进度超过 progressThreshold 的 80% 但价格差距仍很大，需要加速
            double threshold80Percent = progressThreshold * 0.8;
            if (progress > threshold80Percent && priceGapE8 < -priceRangeE8 * convergenceThresholdPercent / 100.0) {
                needsAcceleration = true;
            }
        } else {
            // 下跌周期：在接近结束时需要确保到达 minPrice
            // 如果进度超过 90% 但价格差距仍很大，需要加速
            if (progress > 0.9 && priceGapE8 > priceRangeE8 * convergenceThresholdPercent / 100.0) {
                needsAcceleration = true;
            }
        }
        
        if (needsAcceleration && gapPercent > convergenceThresholdPercent) {
            // 加速因子：差距越大，加速越快
            double accelerationFactor = Math.min(1.0, (gapPercent - convergenceThresholdPercent) / convergenceThresholdPercent);
            // 调整量：向目标价格方向推动（限制调整幅度，避免过度修正）
            double adjustmentE8 = priceGapE8 * accelerationFactor * 0.15; // 0.15 是调整强度，可以微调
            return adjustmentE8;
        }
        
        return 0.0;
    }

    /**
     * 执行刷量策略的核心流程："先铺路（Maker），再走路（Taker）"。
     * 
     * @param oldPriceE8 旧价格
     * @param newPriceE8 新目标价格
     * @param currentTimeMs 当前时间（毫秒时间戳）
     */
    private void executeVolumeStrategy(double oldPriceE8, double newPriceE8, long currentTimeMs) {
        long targetPriceE8 = (long) newPriceE8;
        
        // 步骤一：铺路（Maker）- 在目标价格上下挂单，提供流动性
        // 传递价格变化方向，确保挂单不会立即成交
        boolean isPriceRising = newPriceE8 > oldPriceE8;
        LOG.info(isPriceRising?"价格上涨,目标价{}":"价格下跌,目标价{}",targetPriceE8);
        placeMakerOrders(targetPriceE8, currentTimeMs, isPriceRising);
        
        // 步骤二：走路（Taker）- 根据价格变化方向，吃掉自己挂的单子
        double priceChangeE8 = newPriceE8 - oldPriceE8;
        if (Math.abs(priceChangeE8) < tickSizeE8 * 0.5) {
            return; // 价格变化太小，跳过
        }
        
        if (priceChangeE8 > 0) {
            // 价格上涨：买入账户组发 Taker 买单，去吃卖出账户组挂的 Maker 卖单
            executeTakerOrder(targetPriceE8, (short) 0, currentTimeMs); // Buy
        } else {
            // 价格下跌：卖出账户组发 Taker 卖单，去吃买入账户组挂的 Maker 买单
            executeTakerOrder(targetPriceE8, (short) 1, currentTimeMs); // Sell
        }
    }

    /**
     * 步骤一：铺路（Maker）- 在目标价格上下挂单，提供流动性。
     * <p>
     * 逻辑：
     * - 买入账户组：挂多档买单（Maker）
     * - 卖出账户组：挂多档卖单（Maker）
     * <p>
     * 挂单策略：
     * - 如果价格上涨：买单应该在当前订单簿卖一价下方挂单，避免立即成交
     * - 如果价格下跌：卖单应该在当前订单簿买一价上方挂单，避免立即成交
     * - 在目标价格上下各挂 5 档订单，让订单簿看起来更充实
     * - 每档价格间隔为 tickSizeE8
     * - 每档数量根据时间进度和成交量进度动态调整
     * 
     * @param targetPriceE8 目标价格（放大 10^8 倍）
     * @param currentTimeMs 当前时间（毫秒时间戳）
     * @param isPriceRising 价格是否在上涨（true=上涨，false=下跌）
     */
    private void placeMakerOrders(long targetPriceE8, long currentTimeMs, boolean isPriceRising) {
        final int MAKER_DEPTH = makerCounts; // 每边挂 多少 档订单
        
        // 获取当前订单簿价格
        Exchange dydxExchange = Exchange.fromId(exchangeId);
        ILocalOrderBook orderBook = lobManager.getOrderBook(dydxExchange, symbolId);
        long bestAskE8 = orderBook.bestAskE8();
        long bestBidE8 = orderBook.bestBidE8();
        
        // 买入账户组：挂多档买单
        if (isPriceRising && bestAskE8 > 0 && bestAskE8 <= maxPriceE8) {
            //todo  这里还是要铺路  否则回调会一下子就砸下来了
            // 价格上涨：买单应该在卖一价下方挂单，避免立即成交
            // 从卖一价下方开始挂单，向下挂 5 档
            // 价格上涨时，买单是"被动"的，使用随机小数量（在 minKeepOrderQtyE8 到 minKeepOrderQtyE8 * 2 之间）
            long startBuyPriceE8 = bestAskE8 - tickSizeE8; // 卖一价下方第一档
            long maxKeepOrderQtyE8 = minOrderQtyE8 * 2; // 上限：最小数量的 2 倍
            for (int i = 0; i < MAKER_DEPTH; i++) {
                long buyPriceE8 = startBuyPriceE8 - i * tickSizeE8;
                if (buyPriceE8 < minPriceE8) {
                    break; // 超出价格范围，停止挂单
                }
                // 生成随机小数量（minKeepOrderQtyE8 到 maxKeepOrderQtyE8 之间）
                long randomQtyE8 = minOrderQtyE8 + (long) (random.nextDouble() * (maxKeepOrderQtyE8 - minOrderQtyE8));
                LOG.info("铺路，买，价格{}，数量：{}",buyPriceE8,randomQtyE8);
                placeMakerBuyOrderWithQty(buyPriceE8, randomQtyE8, currentTimeMs);
            }
        } else {
            // 价格下跌或订单簿无效：在目标价格下方挂单（传统方式）
            for (int i = 1; i <= MAKER_DEPTH; i++) {
                long buyPriceE8 = targetPriceE8 - i * tickSizeE8;
                if (buyPriceE8 < minPriceE8) {
                    break; // 超出价格范围，停止挂单
                }
                placeMakerBuyOrder(buyPriceE8, currentTimeMs, false); // 使用动态调整数量

            }
        }
        
        // 卖出账户组：挂多档卖单
        if (!isPriceRising && bestBidE8 > 0 && bestAskE8 >= minPriceE8) {
            //todo  这里还是要铺路  否则回调会一下子就拉上去了
            // 价格下跌：卖单应该在买一价上方挂单，避免立即成交
            // 从买一价上方开始挂单，向上挂 5 档
            // 价格下跌时，卖单是"被动"的，使用随机小数量（在 minKeepOrderQtyE8 到 minKeepOrderQtyE8 * 2 之间）
            long startSellPriceE8 = bestBidE8 + tickSizeE8; // 买一价上方第一档
            long maxKeepOrderQtyE8 = minOrderQtyE8 * 2; // 上限：最小数量的 2 倍
            for (int i = 0; i < MAKER_DEPTH; i++) {
                long sellPriceE8 = startSellPriceE8 + i * tickSizeE8;
                if (sellPriceE8 > maxPriceE8) {
                    break; // 超出价格范围，停止挂单
                }
                // 生成随机小数量（minKeepOrderQtyE8 到 maxKeepOrderQtyE8 之间）
                long randomQtyE8 = minOrderQtyE8 + (long) (random.nextDouble() * (maxKeepOrderQtyE8 - minOrderQtyE8));
                LOG.info("铺路，卖，价格{}，数量：{}",sellPriceE8,randomQtyE8);
                placeMakerSellOrderWithQty(sellPriceE8, randomQtyE8, currentTimeMs);
            }
        } else {
            // 价格上涨或订单簿无效：在目标价格上方挂单（传统方式）
            for (int i = 1; i <= MAKER_DEPTH; i++) {
                long sellPriceE8 = targetPriceE8 + i * tickSizeE8;
                if (sellPriceE8 > maxPriceE8) {
                    break; // 超出价格范围，停止挂单
                }
                placeMakerSellOrder(sellPriceE8, currentTimeMs, false); // 使用动态调整数量
            }
        }
    }

    /**
     * 买入账户组挂 Maker 买单。
     * 
     * @param priceE8 挂单价格（放大 10^8 倍）
     * @param currentTimeMs 当前时间（毫秒时间戳）
     * @param useMinQty 是否使用最小数量（true=使用 minOrderQtyE8，false=使用动态调整数量）
     */
    private void placeMakerBuyOrder(long priceE8, long currentTimeMs, boolean useMinQty) {
        // 根据参数决定使用最小数量还是动态调整数量
        long qtyE8 = useMinQty ? minKeepOrderQtyE8 : generateRandomOrderQty(currentTimeMs);
        LOG.info("铺路，买，价格{}，数量：{}",priceE8,qtyE8);
        short accountId = positionManager.selectAccountWithBalance(buyAccountIds, quoteAssetId, calculateRequiredQuoteBalance(priceE8, qtyE8));
        if (accountId == -1) {
            return;
        }
        
        OrderCommand cmd = createOrderCommand(accountId, (short) 0, priceE8, qtyE8);
        submitOrder(cmd, "Maker Buy");
    }

    /**
     * 买入账户组挂 Maker 买单（指定数量）。
     * 
     * @param priceE8 挂单价格（放大 10^8 倍）
     * @param qtyE8 订单数量（放大 10^8 倍）
     * @param currentTimeMs 当前时间（毫秒时间戳）
     */
    private void placeMakerBuyOrderWithQty(long priceE8, long qtyE8, long currentTimeMs) {
        short accountId = positionManager.selectAccountWithBalance(buyAccountIds, quoteAssetId, calculateRequiredQuoteBalance(priceE8, qtyE8));
        if (accountId == -1) {
            return;
        }
        
        OrderCommand cmd = createOrderCommand(accountId, (short) 0, priceE8, qtyE8);
        submitOrder(cmd, "Maker Buy");
    }

    /**
     * 卖出账户组挂 Maker 卖单。
     * 
     * @param priceE8 挂单价格（放大 10^8 倍）
     * @param currentTimeMs 当前时间（毫秒时间戳）
     * @param useMinQty 是否使用最小数量（true=使用 minOrderQtyE8，false=使用动态调整数量）
     */
    private void placeMakerSellOrder(long priceE8, long currentTimeMs, boolean useMinQty) {
        // 根据参数决定使用最小数量还是动态调整数量
        long qtyE8 = useMinQty ? minKeepOrderQtyE8 : generateRandomOrderQty(currentTimeMs);
        LOG.info("铺路，卖，价格{}，数量：{}",priceE8,qtyE8);
        short accountId = positionManager.selectAccountWithBalance(sellAccountIds, baseAssetId, qtyE8);
        if (accountId == -1) {
            return;
        }
        
        OrderCommand cmd = createOrderCommand(accountId, (short) 1, priceE8, qtyE8);
        submitOrder(cmd, "Maker Sell");
    }

    /**
     * 卖出账户组挂 Maker 卖单（指定数量）。
     * 
     * @param priceE8 挂单价格（放大 10^8 倍）
     * @param qtyE8 订单数量（放大 10^8 倍）
     * @param currentTimeMs 当前时间（毫秒时间戳）
     */
    private void placeMakerSellOrderWithQty(long priceE8, long qtyE8, long currentTimeMs) {
        short accountId = positionManager.selectAccountWithBalance(sellAccountIds, baseAssetId, qtyE8);
        if (accountId == -1) {
            return;
        }
        
        OrderCommand cmd = createOrderCommand(accountId, (short) 1, priceE8, qtyE8);
        submitOrder(cmd, "Maker Sell");
    }
    
    /**
     * 生成随机订单数量（在 minOrderQtyE8 ~ maxOrderQtyE8 范围内）。
     * <p>
     * 根据时间进度和成交量进度动态调整订单数量：
     * - 如果时间进度 > 成交量进度（进度落后）：放大订单数量（最大2.5倍）
     * - 如果时间进度 < 成交量进度（进度超前）：缩小订单数量（最小0.5倍）
     * - 如果进度匹配：使用正常范围
     * 
     * @param currentTimeMs 当前时间（毫秒时间戳），用于计算时间进度
     * @return 随机订单数量（放大 10^8 倍）
     */
    private long generateRandomOrderQty(long currentTimeMs) {
        // 计算时间进度（0.0 ~ 1.0）
        long elapsedMs = currentTimeMs - cycleStartTime;
        double timeProgress = Math.min(1.0, (double) elapsedMs / cycleDurationMs);
        
        // 计算成交量进度（0.0 ~ 1.0）
        double volumeProgress = enableVolumeTarget && targetVolumeE8 > 0 
            ? Math.min(1.0, (double) accumulatedVolumeE8 / targetVolumeE8)
            : timeProgress; // 如果未启用目标量，使用时间进度作为参考
        
        // 计算进度差异（正值表示成交量落后，负值表示成交量超前）
        double progressDiff = timeProgress - volumeProgress;
        
        // 根据进度差异计算调整因子
        // progressDiff > 0：成交量落后，需要加速（放大订单）
        // progressDiff < 0：成交量超前，需要减速（缩小订单）
        double adjustmentFactor = 1.0;
        if (Math.abs(progressDiff) > 0.05) { // 只有差异超过5%才调整
            if (progressDiff > 0) {
                // 成交量落后：放大订单（最大2.5倍）
                // progressDiff = 0.1 时，factor = 1.5
                // progressDiff = 0.3 时，factor = 2.5
                adjustmentFactor = 1.0 + Math.min(1.5, progressDiff * 5.0);
            } else {
                // 成交量超前：缩小订单（最小0.5倍）
                // progressDiff = -0.1 时，factor = 0.75
                // progressDiff = -0.3 时，factor = 0.5
                adjustmentFactor = 1.0 + Math.max(-0.5, progressDiff * 2.5);
            }
        }
        
        // 计算调整后的订单数量范围
        double adjustedMinQtyE8 = minOrderQtyE8 * adjustmentFactor;
        double adjustedMaxQtyE8 = maxOrderQtyE8 * adjustmentFactor;
        
        // 确保调整后的范围合理（不能小于最小值，也不能过大）
        adjustedMinQtyE8 = Math.max(minKeepOrderQtyE8, adjustedMinQtyE8); // 至少为1
        adjustedMaxQtyE8 = Math.max(adjustedMinQtyE8 + minKeepOrderQtyE8, adjustedMaxQtyE8);
        
        // 生成随机订单数量
        double ratio = random.nextDouble(); // 0.0 ~ 1.0
        double qtyE8 = adjustedMinQtyE8 + ratio * (adjustedMaxQtyE8 - adjustedMinQtyE8);
        
        // 对齐到 tickSize（如果需要）
        return (long) Math.round(qtyE8);
    }

    /**
     * 步骤二：走路（Taker）- 根据目标价格和方向，指挥账户组发起攻击，吃掉自己挂的单子。
     * <p>
     * 逻辑：
     * - Side=Buy（拉升价格）：买入账户组发 Taker 买单，去吃卖出账户组挂的 Maker 卖单
     * - Side=Sell（砸低价格）：卖出账户组发 Taker 卖单，去吃买入账户组挂的 Maker 买单
     * 
     * @param targetPriceE8 目标价格（放大 10^8 倍）
     * @param side 方向：0=Buy（拉升），1=Sell（砸低）
     * @param currentTimeMs 当前时间（毫秒时间戳）
     */
    private void executeTakerOrder(long targetPriceE8, short side, long currentTimeMs) {
        Exchange dydxExchange = Exchange.fromId(exchangeId);
        ILocalOrderBook orderBook = lobManager.getOrderBook(dydxExchange, symbolId);
        
        short[] takerAccountIds;
        short requiredAssetId;
        long takerPriceE8;
        long takerQtyE8;
        
        if (side == 0) {
            // Buy：买入账户组发 Taker 买单，去吃卖出账户组挂的 Maker 卖单
            takerAccountIds = buyAccountIds;
            requiredAssetId = quoteAssetId; // 买单需要 USDT
            
            long bestAskE8 = orderBook.bestAskE8();
            if (bestAskE8 <= 0) {
                LOG.warn("Taker Buy: symbolId={}, 订单簿无卖单，跳过", symbolId);
                return;
            }
            boolean isRandomOrder = false;
            // 判断目标价格与当前卖一价的关系
            if (targetPriceE8 >= bestAskE8) {
                // 目标价格 >= 卖一价：需要吃掉从 bestAskE8 到 targetPriceE8 之间的所有卖单
                long cumulativeQtyE8 = orderBook.calculateCumulativeAskQty(bestAskE8, targetPriceE8);

                if (cumulativeQtyE8 > 0) {
                    // 使用累计数量，确保能够达到目标价格
                    takerQtyE8 = cumulativeQtyE8;
                    // 使用目标价格作为 Taker 买单价格（或使用卖一价确保立即成交）
                    takerPriceE8 = targetPriceE8;
                } else {
                    // 如果累计数量为0，使用动态调整的数量
                    takerQtyE8 = generateRandomOrderQty(currentTimeMs);
                    takerPriceE8 = bestAskE8;
                    isRandomOrder = true;
                }
            } else {
                isRandomOrder = true;
                // 目标价格 < 卖一价：无法通过吃单达到目标价格，使用动态调整的数量
                takerQtyE8 = generateRandomOrderQty(currentTimeMs);
                takerPriceE8 = bestAskE8;
            }
            
            long requiredBalanceE8 = calculateRequiredQuoteBalance(takerPriceE8, takerQtyE8);
            short accountId = positionManager.selectAccountWithBalance(takerAccountIds, requiredAssetId, requiredBalanceE8);
            if (accountId == -1) {
                LOG.warn("Taker Buy: symbolId={}, 买入账户组余额不足，需要 {} USDT", symbolId, requiredBalanceE8 / (double) ScaleConstants.SCALE_E8);
                return;
            }
            LOG.info("taker 触发买单: symbolId={}, 价格={}, 数量={},是否动态生成的数量：{}", symbolId, takerPriceE8 / (double) ScaleConstants.SCALE_E8, takerQtyE8 / (double) ScaleConstants.SCALE_E8,isRandomOrder);
            OrderCommand cmd = createOrderCommand(accountId, side, takerPriceE8, takerQtyE8);
            submitOrder(cmd, "Taker Buy");
            
        } else {
            // Sell：卖出账户组发 Taker 卖单，去吃买入账户组挂的 Maker 买单
            takerAccountIds = sellAccountIds;
            requiredAssetId = baseAssetId; // 卖单需要 ETH
            boolean isRandomOrder = false;
            long bestBidE8 = orderBook.bestBidE8();
            if (bestBidE8 <= 0) {
                LOG.warn("Taker Sell: symbolId={}, 订单簿无买单，跳过", symbolId);
                return;
            }
            
            // 判断目标价格与当前买一价的关系
            if (targetPriceE8 <= bestBidE8) {
                // 目标价格 <= 买一价：需要吃掉从 bestBidE8 到 targetPriceE8 之间的所有买单
                long cumulativeQtyE8 = orderBook.calculateCumulativeBidQty(bestBidE8, targetPriceE8);
                if (cumulativeQtyE8 > 0) {
                    // 使用累计数量，确保能够达到目标价格
                    takerQtyE8 = cumulativeQtyE8;
                    // 使用目标价格作为 Taker 卖单价格（或使用买一价确保立即成交）
                    takerPriceE8 = targetPriceE8; // 使用买一价确保立即成交
                } else {
                    // 如果累计数量为0，使用动态调整的数量
                    takerQtyE8 = generateRandomOrderQty(currentTimeMs);
                    takerPriceE8 = bestBidE8;
                    isRandomOrder = true;
                }
            } else {
                isRandomOrder = true;
                // 目标价格 > 买一价：无法通过吃单达到目标价格，使用动态调整的数量
                takerQtyE8 = generateRandomOrderQty(currentTimeMs);
                takerPriceE8 = bestBidE8;
            }
            
            short accountId = positionManager.selectAccountWithBalance(takerAccountIds, requiredAssetId, takerQtyE8);
            if (accountId == -1) {
                LOG.warn("Taker Sell: symbolId={}, 卖出账户组余额不足，需要 {} ETH", symbolId, takerQtyE8 / (double) ScaleConstants.SCALE_E8);
                return;
            }
            LOG.info("taker 触发卖单: symbolId={}, 价格={}, 数量={}，是否动态生成的数量：{}", symbolId, takerPriceE8 / (double) ScaleConstants.SCALE_E8, takerQtyE8 / (double) ScaleConstants.SCALE_E8,isRandomOrder);
            OrderCommand cmd = createOrderCommand(accountId, side, takerPriceE8, takerQtyE8);
            submitOrder(cmd, "Taker Sell");
        }
    }

    /**
     * 计算买单所需的报价资产余额（简化版本，避免溢出）。
     * 
     * @param priceE8 价格（放大 10^8 倍）
     * @param qtyE8 数量（放大 10^8 倍）
     * @return 所需余额（放大 10^8 倍）
     */
    private long calculateRequiredQuoteBalance(long priceE8, long qtyE8) {
        // 简化计算：price * qty / SCALE_E8
        // 使用 double 避免溢出，然后转回 long
        double price = priceE8 / (double) ScaleConstants.SCALE_E8;
        double qty = qtyE8 / (double) ScaleConstants.SCALE_E8;
        double required = price * qty;
        return (long) (required * ScaleConstants.SCALE_E8);
    }

    /**
     * 创建订单命令。
     */
    private OrderCommand createOrderCommand(short accountId, short side, long priceE8, long qtyE8) {
        OrderCommand cmd = new OrderCommand();
        cmd.accountId = accountId;
        cmd.symbolId = symbolId;
        cmd.exchangeId = exchangeId;
        cmd.side = side;
        cmd.priceE8 = priceE8;
        cmd.qtyE8 = qtyE8;
        return cmd;
    }
    
    /**
     * 创建订单命令（带 goodTilTimeInSeconds）。
     */
    private OrderCommand createOrderCommandWithTime(short accountId, short side, long priceE8, long qtyE8, int goodTilTimeInSeconds) {
        OrderCommand cmd = createOrderCommand(accountId, side, priceE8, qtyE8);
        cmd.goodTilTimeInSeconds = goodTilTimeInSeconds;
        return cmd;
    }

    /**
     * 提交订单。
     */
    private void submitOrder(OrderCommand cmd, String orderType) {
        try {
            oms.submitOrder(cmd);
//            LOG.debug("{} 订单已提交: accountId={}, side={}, price={}, qty={}",
//                orderType,
//                cmd.accountId,
//                cmd.side == 0 ? "BUY" : "SELL",
//                cmd.priceE8 / (double) ScaleConstants.SCALE_E8,
//                cmd.qtyE8 / (double) ScaleConstants.SCALE_E8);
        } catch (Exception e) {
            LOG.error("{} 订单提交失败: symbolId={}", orderType, symbolId, e);
        }
    }
    
    /**
     * 首次初始化时，挂5笔大单。
     * <p>
     * 逻辑：
     * - 买单：在 minPriceE8 下方挂5笔，例如 minPriceE8=30，则在 29.99, 29.98, 29.97, 29.96, 29.95
     * - 卖单：在 maxPriceE8 上方挂5笔，例如 maxPriceE8=45，则在 45.01, 45.02, 45.03, 45.04, 45.05
     * - 每笔订单数量：targetVolumeE8 的 1/10 到 2/10，但如果最小值<10或最大值<15，则使用10-15
     * - goodTilTimeInSeconds = cycleDurationMs / 1000
     * 
     * @param currentTimeMs 当前时间（毫秒时间戳）
     */
    /**
     * 获取策略运行状态信息。
     * 
     * @return 策略状态信息对象
     */
    public StrategyStatusInfo getStatusInfo() {
        StrategyStatusInfo info = new StrategyStatusInfo();
        
        // 运行状态
        info.accumulatedVolumeE8 = accumulatedVolumeE8;
        info.cycleStartTime = cycleStartTime;
        info.currentTargetPriceE8 = currentTargetPriceE8;
        info.isRising = isRising;
        info.initialized = initialized;
        info.inCorrection = inCorrection;
        
        // 配置参数
        info.minPriceE8 = minPriceE8;
        info.maxPriceE8 = maxPriceE8;
        info.targetVolumeE8 = targetVolumeE8;
        info.cycleDurationMs = cycleDurationMs;
        info.volatilityPercent = volatilityPercent;
        info.enableVolumeTarget = enableVolumeTarget;
        info.makerCounts = makerCounts;
        info.noiseFactor = noiseFactor;
        info.minOrderQtyE8 = minOrderQtyE8;
        info.maxOrderQtyE8 = maxOrderQtyE8;
        
        // 计算进度百分比
        if (cycleDurationMs > 0) {
            long elapsedMs = System.currentTimeMillis() - cycleStartTime;
            info.cycleProgressPercent = Math.min(100.0, (elapsedMs * 100.0) / cycleDurationMs);
        } else {
            info.cycleProgressPercent = 0.0;
        }
        
        // 计算成交量完成百分比
        if (enableVolumeTarget && targetVolumeE8 > 0) {
            info.volumeProgressPercent = Math.min(100.0, (accumulatedVolumeE8 * 100.0) / targetVolumeE8);
        } else {
            info.volumeProgressPercent = 0.0;
        }
        
        return info;
    }
    
    /**
     * 策略状态信息。
     */
    public static class StrategyStatusInfo {
        // 运行状态
        public long accumulatedVolumeE8;      // 累计成交量
        public long cycleStartTime;           // 周期开始时间
        public double currentTargetPriceE8;    // 当前目标价格
        public boolean isRising;              // 是否上涨
        public boolean initialized;           // 是否已初始化
        public boolean inCorrection;          // 是否处于回调中
        
        // 配置参数
        public long minPriceE8;               // 最低价
        public long maxPriceE8;               // 最高价
        public long targetVolumeE8;           // 目标量
        public long cycleDurationMs;          // 周期时长
        public double volatilityPercent;      // 波动率百分比
        public boolean enableVolumeTarget;    // 是否启用目标量控制
        public int makerCounts;               // Maker订单数量
        public double noiseFactor;            // 噪声因子
        public long minOrderQtyE8;            // 最小订单数量
        public long maxOrderQtyE8;            // 最大订单数量
        
        // 计算得出的进度
        public double cycleProgressPercent;   // 周期进度百分比
        public double volumeProgressPercent; // 成交量完成百分比
    }

    private void placeInitialLargeOrders(long currentTimeMs) {
        // 计算订单数量范围（targetVolumeE8 的 1/10 到 2/10）
        long minQtyE8 = targetVolumeE8 / 10;
        long maxQtyE8 = targetVolumeE8 * 2 / 10;
        
        // 如果范围最小值少于10或最大值小于15，则按10-15来
        long minQtyE8Scaled = minQtyE8 / ScaleConstants.SCALE_E8;
        long maxQtyE8Scaled = maxQtyE8 / ScaleConstants.SCALE_E8;
        if (minQtyE8Scaled < 10 || maxQtyE8Scaled < 15) {
            minQtyE8 = 10L * ScaleConstants.SCALE_E8;
            maxQtyE8 = 15L * ScaleConstants.SCALE_E8;
        }
        
        // 计算 goodTilTimeInSeconds
        int goodTilTimeInSeconds = (int) (cycleDurationMs / 1000);
        
        LOG.info("首次初始化：开始挂5笔大单: symbolId={}, 数量范围: {} - {}，goodTilTimeInSeconds: {}",
            symbolId,
            minQtyE8 / (double) ScaleConstants.SCALE_E8,
            maxQtyE8 / (double) ScaleConstants.SCALE_E8,
            goodTilTimeInSeconds);
        
        // 挂5笔买单：在 minPriceE8 下方
        // 例如 minPriceE8=30，则在 29.99, 29.98, 29.97, 29.96, 29.95 各挂一笔
        for (int i = 1; i <= 5; i++) {
            long buyPriceE8 = minPriceE8 - i * tickSizeE8;
            if (buyPriceE8 <= 0) {
                LOG.warn("买单价格超出范围，跳过: symbolId={}, 价格={}", symbolId, buyPriceE8 / (double) ScaleConstants.SCALE_E8);
                break;
            }
            
            // 生成随机数量（在 minQtyE8 到 maxQtyE8 之间）
            long randomQtyE8 = minQtyE8 + (long) (random.nextDouble() * (maxQtyE8 - minQtyE8));
            
            // 选择账户
            short accountId = positionManager.selectAccountWithBalance(
                buyAccountIds, 
                quoteAssetId, 
                calculateRequiredQuoteBalance(buyPriceE8, randomQtyE8)
            );
            if (accountId == -1) {
                LOG.warn("买单账户余额不足: symbolId={}, 价格: {}, 数量: {}", 
                    symbolId,
                    buyPriceE8 / (double) ScaleConstants.SCALE_E8,
                    randomQtyE8 / (double) ScaleConstants.SCALE_E8);
                continue;
            }
            
            OrderCommand cmd = createOrderCommandWithTime(accountId, (short) 0, buyPriceE8, randomQtyE8, goodTilTimeInSeconds);
            submitOrder(cmd, "Initial Large Buy");
            LOG.info("首次初始化买单: symbolId={}, 价格={}, 数量={}, accountId={}", 
                symbolId,
                buyPriceE8 / (double) ScaleConstants.SCALE_E8,
                randomQtyE8 / (double) ScaleConstants.SCALE_E8,
                accountId);
        }
        
        // 挂5笔卖单：在 maxPriceE8 上方
        // 例如 maxPriceE8=45，则在 45.01, 45.02, 45.03, 45.04, 45.05 各挂一笔
        for (int i = 1; i <= 5; i++) {
            long sellPriceE8 = maxPriceE8 + i * tickSizeE8;
            
            // 生成随机数量（在 minQtyE8 到 maxQtyE8 之间）
            long randomQtyE8 = minQtyE8 + (long) (random.nextDouble() * (maxQtyE8 - minQtyE8));
            
            // 选择账户
            short accountId = positionManager.selectAccountWithBalance(
                sellAccountIds, 
                baseAssetId, 
                randomQtyE8
            );
            if (accountId == -1) {
                LOG.warn("卖单账户余额不足: symbolId={}, 价格: {}, 数量: {}", 
                    symbolId,
                    sellPriceE8 / (double) ScaleConstants.SCALE_E8,
                    randomQtyE8 / (double) ScaleConstants.SCALE_E8);
                continue;
            }
            
            OrderCommand cmd = createOrderCommandWithTime(accountId, (short) 1, sellPriceE8, randomQtyE8, goodTilTimeInSeconds);
            submitOrder(cmd, "Initial Large Sell");
            LOG.info("首次初始化卖单: symbolId={}, 价格={}, 数量={}, accountId={}", 
                symbolId,
                sellPriceE8 / (double) ScaleConstants.SCALE_E8,
                randomQtyE8 / (double) ScaleConstants.SCALE_E8,
                accountId);
        }
        
        LOG.info("首次初始化：已完成挂5笔大单: symbolId={}（买单5笔，卖单5笔）", symbolId);
    }
    
    // === Getter 方法（供 AccountBalanceBalancer 使用）===
    
    public short[] getBuyAccountIds() {
        return buyAccountIds.clone();
    }
    
    public short[] getSellAccountIds() {
        return sellAccountIds.clone();
    }
    
    public short getQuoteAssetId() {
        return quoteAssetId;
    }
    
    public short getBaseAssetId() {
        return baseAssetId;
    }
    
    public short getExchangeId() {
        return exchangeId;
    }
}
