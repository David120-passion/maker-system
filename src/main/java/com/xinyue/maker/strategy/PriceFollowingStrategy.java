package com.xinyue.maker.strategy;

import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.OrderCommand;
import com.xinyue.maker.core.lob.OrderBookSnapshot;
import com.xinyue.maker.core.oms.Order;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import org.agrona.collections.IntArrayList;

/**
 * 价格跟随策略：跟随币安订单簿变动，在目标交易所（dYdX）挂单。
 * 
 * 策略逻辑：
 * - 买单目标价：币安买一价 - 价差（spread）
 * - 卖单目标价：币安卖一价 + 价差（spread）
 * - 订单数量：根据币安订单簿深度动态计算
 * - 价格变化阈值：只有币安价格变化超过最小阈值才调整订单
 * - 最小价差检查：目标卖价 - 目标买价 >= 最小价差
 * - 订单维护：非阻塞，状态机+事件驱动模式
 */
public final class PriceFollowingStrategy implements MarketMakingStrategy {
    
    /**
     * 调整状态枚举。
     */
    private enum AdjustState {
        IDLE,              // 空闲状态，可以正常调整订单
        PENDING_CANCEL     // 等待撤单确认，撤单确认后才能挂新单
    }
    
    /**
     * 跟随价差（Spread）。
     * <p>
     * 作用：将币安最优价“平移”成目标交易所的挂单价。
     * - 目标买价 = Binance BestBid - SPREAD_E8
     * - 目标卖价 = Binance BestAsk + SPREAD_E8
     * <p>
     * 目的：给做市挂单预留手续费/滑点空间，避免频繁被动成交导致负期望。
     * <p>
     * 单位：价格放大 1e8（E8）。
     */
    private static final long SPREAD_E8 = 50_000L;

    /**
     * 深度跟随比例（Depth Percentage）。
     * <p>
     * 作用：用币安“最优价档位”的可见数量（bestBidQty/bestAskQty）来动态计算挂单数量：
     * targetQtyE8 = bestQtyE8 * DEPTH_PERCENTAGE，然后再做最小/最大数量夹取（见 MIN/MAX）。
     * <p>
     * 目的：让挂单规模随市场流动性自适应，流动性大时加大规模、流动性小时降低规模。
     * <p>
     * 注意：这是 double（非热路径频繁分配），只用于计算比例，不参与任何对象创建。
     */
    private static final double DEPTH_PERCENTAGE = 0.001;

    /**
     * 最小下单数量（Min Order Size）。
     * <p>
     * 作用：对计算出来的 targetQty 做下限保护，避免数量太小导致：
     * - 交易所最小下单限制/精度限制失败
     * - 成交价值太小不覆盖手续费，策略噪声变大
     * <p>
     * 单位：数量放大 1e8（E8），通常对应 base 资产数量（例如 BTC）。
     */
    private static final long MIN_ORDER_SIZE_E8 = 1_000_000_000L;

    /**
     * 最大下单数量（Max Order Size）。
     * <p>
     * 作用：对 targetQty 做上限保护，避免深度较大时一次挂单过大导致风险暴露过高
     * （库存波动、被单边打穿、撤单失败等情况下的最大敞口）。
     * <p>
     * 单位：数量放大 1e8（E8），通常对应 base 资产数量（例如 BTC）。
     */
    private static final long MAX_ORDER_SIZE_E8 = 500_000_000_000L;

    /**
     * 最小价格变化阈值（Price Change Threshold）。
     * <p>
     * 作用：只有当币安最优买/卖价相对“上一次处理价”变化超过该阈值，策略才会进入订单维护流程，
     * 否则直接忽略本次行情更新（减少无意义撤挂，降低限频压力和消息风暴）。
     * <p>
     * 影响：阈值越小越敏感（更贴价、更频繁撤挂）；阈值越大越钝化（更稳定、但跟随误差更大）。
     * <p>
     * 单位：价格放大 1e8（E8）。
     */
    private static final long PRICE_CHANGE_THRESHOLD_E8 = 10_000L;

    /**
     * 最小目标价差（Minimum Target Spread）。
     * <p>
     * 作用：在计算出 targetBid/targetAsk 后做一次“可盈利性/可行性”检查：
     * 若 (targetAsk - targetBid) &lt; MIN_SPREAD_E8，则不挂单/不调整（直接返回）。
     * <p>
     * 目的：避免在市场极窄价差时挂单导致：
     * - 覆盖不了 maker/taker 费用
     * - 轻微抖动就触发自成交风险与频繁撤挂
     * <p>
     * 单位：价格放大 1e8（E8）。
     */
    private static final long MIN_SPREAD_E8 = 20_000L;
    
    // === 策略状态 ===
    private final OrderManagementSystem oms;
    private final PositionManager positionManager;
    private final short targetSymbolId;  // 目标交易对ID（如 BTCUSDT）
    private final short baseAssetId;      // 基础资产ID（如 BTC）
    private final short quoteAssetId;    // 报价资产ID（如 USDT）
    private final short targetExchangeId; // 目标交易所ID（dYdX）
    
    // 当前挂单信息
    private long currentBidOrderId = 0;   // 当前买单ID（0表示没有）
    private long currentAskOrderId = 0;   // 当前卖单ID（0表示没有）
    
    // 上次价格（用于价格变化阈值检查）
    private long lastBidPriceE8 = 0;
    private long lastAskPriceE8 = 0;
    
    // 状态机
    private AdjustState adjustState = AdjustState.IDLE;
    
    // 待挂新单信息（在 PENDING_CANCEL 状态下保存）
    private long pendingNewBidPriceE8 = 0;
    private long pendingNewAskPriceE8 = 0;
    private long pendingNewBidQtyE8 = 0;
    private long pendingNewAskQtyE8 = 0;
    private long pendingCancelBidOrderId = 0;
    private long pendingCancelAskOrderId = 0;
    
    public PriceFollowingStrategy(OrderManagementSystem oms,
                                  PositionManager positionManager,
                                  short targetSymbolId,
                                  String baseAssetSymbol,  // 如 "BTC"
                                  String quoteAssetSymbol, // 如 "USDT"
                                  Exchange targetExchange) {
        this.oms = oms;
        this.positionManager = positionManager;
        this.targetSymbolId = targetSymbolId;
        this.targetExchangeId = targetExchange.id();
        
        AssetRegistry assetRegistry = AssetRegistry.getInstance();
        this.baseAssetId = assetRegistry.get(baseAssetSymbol);
        this.quoteAssetId = assetRegistry.get(quoteAssetSymbol);
    }
    
    @Override
    public void onDepthUpdate(CoreEvent event, OrderBookSnapshot referenceSnapshot) {
        // 边界检查
        long binanceBidE8 = referenceSnapshot.bestBidE8();
        long binanceAskE8 = referenceSnapshot.bestAskE8();
        
        if (binanceBidE8 <= 0 || binanceAskE8 <= 0 || binanceAskE8 <= binanceBidE8) {
            return; // 无效的订单簿数据
        }
        
        // 价格变化阈值检查：只有价格变化超过阈值才调整订单
        boolean priceChanged = false;
        if (lastBidPriceE8 == 0 || lastAskPriceE8 == 0) {
            // 首次更新，需要初始化订单
            priceChanged = true;
        } else {
            long bidChange = Math.abs(binanceBidE8 - lastBidPriceE8);
            long askChange = Math.abs(binanceAskE8 - lastAskPriceE8);
            if (bidChange >= PRICE_CHANGE_THRESHOLD_E8 || askChange >= PRICE_CHANGE_THRESHOLD_E8) {
                priceChanged = true;
            }
        }
        
        if (!priceChanged) {
            return; // 价格变化未超过阈值，不调整订单
        }
        
        // 计算目标价格
        long targetBidPriceE8 = binanceBidE8 - SPREAD_E8;
        long targetAskPriceE8 = binanceAskE8 + SPREAD_E8;
        
        // 最小价差检查
        if (targetAskPriceE8 - targetBidPriceE8 < MIN_SPREAD_E8) {
            return; // 价差太小，不挂单
        }
        
        // 计算订单数量（根据币安订单簿深度）
        long binanceBidQtyE8 = referenceSnapshot.bestBidQtyE8();
        long binanceAskQtyE8 = referenceSnapshot.bestAskQtyE8();
        
        long targetBidQtyE8 = calculateOrderQty(binanceBidQtyE8);
        long targetAskQtyE8 = calculateOrderQty(binanceAskQtyE8);
        
        // 更新上次价格
        lastBidPriceE8 = binanceBidE8;
        lastAskPriceE8 = binanceAskE8;
        
        // 处理订单调整（非阻塞）
        adjustOrders(targetBidPriceE8, targetAskPriceE8, targetBidQtyE8, targetAskQtyE8);
    }
    
    @Override
    public void onAccountOrderUpdate(CoreEvent event) {
        // 处理撤单确认事件
        if (adjustState != AdjustState.PENDING_CANCEL) {
            return; // 不在等待撤单状态，忽略
        }
        
        // 检查是否是撤单确认（订单状态变为 Canceled）
        boolean isSync = (event.firstUpdateId == -1);
        if (isSync) {
            // 同步模式：重建订单视图，不处理撤单确认
            return;
        }
        
        long clientId = event.clientOidHash;
        Order order = oms.getOrderByClientId(clientId);
        
        if (order == null) {
            return;
        }
        
        // 检查订单是否被取消
        if (order.orderStatus == 6) { // Canceled
            // 检查是否是等待撤单的订单
            if (clientId == pendingCancelBidOrderId) {
                pendingCancelBidOrderId = 0;
                // 余额释放由 OMS 统一处理，策略层不需要关心
            }
            if (clientId == pendingCancelAskOrderId) {
                pendingCancelAskOrderId = 0;
                // 余额释放由 OMS 统一处理，策略层不需要关心
            }
            
            // 检查是否所有撤单都已确认
            if (pendingCancelBidOrderId == 0 && pendingCancelAskOrderId == 0) {
                // 所有撤单都已确认，可以挂新单
                if (pendingNewBidPriceE8 > 0 && pendingNewBidQtyE8 > 0) {
                    submitBidOrder(pendingNewBidPriceE8, pendingNewBidQtyE8);
                    pendingNewBidPriceE8 = 0;
                    pendingNewBidQtyE8 = 0;
                }
                if (pendingNewAskPriceE8 > 0 && pendingNewAskQtyE8 > 0) {
                    submitAskOrder(pendingNewAskPriceE8, pendingNewAskQtyE8);
                    pendingNewAskPriceE8 = 0;
                    pendingNewAskQtyE8 = 0;
                }
                
                // 重置状态
                adjustState = AdjustState.IDLE;
            }
        }
    }
    
    /**
     * 调整订单（非阻塞）。
     */
    private void adjustOrders(long targetBidPriceE8, long targetAskPriceE8,
                              long targetBidQtyE8, long targetAskQtyE8) {
        // 如果正在等待撤单确认，不处理新的调整请求
        if (adjustState == AdjustState.PENDING_CANCEL) {
            return;
        }
        
        // 检查是否需要调整买单
        boolean needAdjustBid = false;
        if (currentBidOrderId == 0) {
            // 没有买单，需要挂新单
            needAdjustBid = true;
        } else {
            Order bidOrder = oms.getOrder(currentBidOrderId);
            if (bidOrder == null || !bidOrder.isActive()) {
                // 订单不存在或已失效，需要挂新单
                needAdjustBid = true;
                currentBidOrderId = 0;
            } else if (bidOrder.priceE8 != targetBidPriceE8) {
                // 价格不同，需要调整
                needAdjustBid = true;
            }
        }
        
        // 检查是否需要调整卖单
        boolean needAdjustAsk = false;
        if (currentAskOrderId == 0) {
            // 没有卖单，需要挂新单
            needAdjustAsk = true;
        } else {
            Order askOrder = oms.getOrder(currentAskOrderId);
            if (askOrder == null || !askOrder.isActive()) {
                // 订单不存在或已失效，需要挂新单
                needAdjustAsk = true;
                currentAskOrderId = 0;
            } else if (askOrder.priceE8 != targetAskPriceE8) {
                // 价格不同，需要调整
                needAdjustAsk = true;
            }
        }
        
        // 如果目标价已有活跃订单，不调整
        if (!needAdjustBid && !needAdjustAsk) {
            return; // 无需调整
        }
        
        // 获取旧订单信息（用于自成交风险检查）
        Order oldBidOrder = (currentBidOrderId != 0) ? oms.getOrder(currentBidOrderId) : null;
        Order oldAskOrder = (currentAskOrderId != 0) ? oms.getOrder(currentAskOrderId) : null;
        
        // 自成交风险检查
        boolean wouldSelfTrade = wouldSelfTrade(targetBidPriceE8, targetAskPriceE8, oldBidOrder, oldAskOrder);
        
        if (wouldSelfTrade) {
            // 存在自成交风险，采用"先撤后挂"策略
            handleSelfTradeRisk(targetBidPriceE8, targetAskPriceE8, targetBidQtyE8, targetAskQtyE8,
                               oldBidOrder, oldAskOrder);
        } else {
            // 不存在自成交风险，检查余额后决定挂单顺序
            handleNormalAdjust(targetBidPriceE8, targetAskPriceE8, targetBidQtyE8, targetAskQtyE8,
                              oldBidOrder, oldAskOrder);
        }
    }
    
    /**
     * 检查是否存在自成交风险。
     */
    private boolean wouldSelfTrade(long newBidPriceE8, long newAskPriceE8,
                                   Order oldBidOrder, Order oldAskOrder) {
        // 如果新卖价 <= 旧买价，或新买价 >= 旧卖价，存在自成交风险
        if (oldBidOrder != null && oldBidOrder.isActive() && newAskPriceE8 <= oldBidOrder.priceE8) {
            return true;
        }
        if (oldAskOrder != null && oldAskOrder.isActive() && newBidPriceE8 >= oldAskOrder.priceE8) {
            return true;
        }
        return false;
    }
    
    /**
     * 处理自成交风险：先撤后挂。
     */
    private void handleSelfTradeRisk(long targetBidPriceE8, long targetAskPriceE8,
                                     long targetBidQtyE8, long targetAskQtyE8,
                                     Order oldBidOrder, Order oldAskOrder) {
        // 保存待挂新单信息
        pendingNewBidPriceE8 = targetBidPriceE8;
        pendingNewAskPriceE8 = targetAskPriceE8;
        pendingNewBidQtyE8 = targetBidQtyE8;
        pendingNewAskQtyE8 = targetAskQtyE8;
        
        // 设置状态
        adjustState = AdjustState.PENDING_CANCEL;
        
        // 立即发送撤单请求（异步，非阻塞）
        if (oldBidOrder != null && oldBidOrder.isActive() && currentBidOrderId != 0) {
            pendingCancelBidOrderId = currentBidOrderId;
            oms.cancelOrder(currentBidOrderId);
            currentBidOrderId = 0;
        }
        if (oldAskOrder != null && oldAskOrder.isActive() && currentAskOrderId != 0) {
            pendingCancelAskOrderId = currentAskOrderId;
            oms.cancelOrder(currentAskOrderId);
            currentAskOrderId = 0;
        }
        
        // onDepthUpdate 立即返回，不阻塞主线程
    }
    
    /**
     * 处理正常调整：检查余额后决定挂单顺序。
     */
    private void handleNormalAdjust(long targetBidPriceE8, long targetAskPriceE8,
                                   long targetBidQtyE8, long targetAskQtyE8,
                                   Order oldBidOrder, Order oldAskOrder) {
        // 计算所需余额
        long requiredQuoteE8 = targetBidPriceE8 * targetBidQtyE8 / 100_000_000L; // 买单需要 USDT
        long requiredBaseE8 = targetAskQtyE8; // 卖单需要 BTC
        
        // 获取账户（简化：使用第一个账户，后续可扩展为账户选择逻辑）
        // 注意：需要考虑待取消订单的锁定余额
        IntArrayList accounts = positionManager.getAccountsByTotalBalance(quoteAssetId, requiredQuoteE8);
        if (accounts.isEmpty()) {
            // 余额不足，先撤旧订单，保存待挂新单信息
            handleInsufficientBalance(targetBidPriceE8, targetAskPriceE8, targetBidQtyE8, targetAskQtyE8,
                                     oldBidOrder, oldAskOrder);
            return;
        }
        
        int accountId = accounts.getInt(0);
        
//         检查卖单余额（需要基础资产，考虑待取消订单的锁定余额）
        IntArrayList baseAccounts = positionManager.getAccountsByTotalBalance(baseAssetId, requiredBaseE8);
        if (baseAccounts.isEmpty() || !baseAccounts.contains(accountId)) {
            // 余额不足，先撤旧订单，保存待挂新单信息
            handleInsufficientBalance(targetBidPriceE8, targetAskPriceE8, targetBidQtyE8, targetAskQtyE8,
                                     oldBidOrder, oldAskOrder);
            return;
        }
        
        // 余额足够：先挂新订单，再撤旧订单（异步，非阻塞）
        if (oldBidOrder != null && oldBidOrder.isActive() && currentBidOrderId != 0) {
            // 先挂新买单
            submitBidOrder(targetBidPriceE8, targetBidQtyE8);
            // 再撤旧买单（异步）
            oms.cancelOrder(currentBidOrderId);
            currentBidOrderId = 0;
        } else if (currentBidOrderId == 0) {
            // 没有旧买单，直接挂新买单
            submitBidOrder(targetBidPriceE8, targetBidQtyE8);
        }
        
        if (oldAskOrder != null && oldAskOrder.isActive() && currentAskOrderId != 0) {
            // 先挂新卖单
            submitAskOrder(targetAskPriceE8, targetAskQtyE8);
            // 再撤旧卖单（异步）
            oms.cancelOrder(currentAskOrderId);
            currentAskOrderId = 0;
        } else if (currentAskOrderId == 0) {
            // 没有旧卖单，直接挂新卖单
            submitAskOrder(targetAskPriceE8, targetAskQtyE8);
        }
    }
    
    /**
     * 处理余额不足的情况：先撤旧订单，保存待挂新单信息。
     */
    private void handleInsufficientBalance(long targetBidPriceE8, long targetAskPriceE8,
                                          long targetBidQtyE8, long targetAskQtyE8,
                                          Order oldBidOrder, Order oldAskOrder) {
        // 保存待挂新单信息
        pendingNewBidPriceE8 = targetBidPriceE8;
        pendingNewAskPriceE8 = targetAskPriceE8;
        pendingNewBidQtyE8 = targetBidQtyE8;
        pendingNewAskQtyE8 = targetAskQtyE8;
        
        // 设置状态
        adjustState = AdjustState.PENDING_CANCEL;
        
        // 先撤旧订单（异步，非阻塞）
        if (oldBidOrder != null && oldBidOrder.isActive() && currentBidOrderId != 0) {
            pendingCancelBidOrderId = currentBidOrderId;
            oms.cancelOrder(currentBidOrderId);
            currentBidOrderId = 0;
        }
        if (oldAskOrder != null && oldAskOrder.isActive() && currentAskOrderId != 0) {
            pendingCancelAskOrderId = currentAskOrderId;
            oms.cancelOrder(currentAskOrderId);
            currentAskOrderId = 0;
        }
        
        // 在撤单确认后挂新单（通过 onAccountOrderUpdate 处理）
    }
    
    /**
     * 提交买单。
     */
    private void submitBidOrder(long priceE8, long qtyE8) {
        // 获取账户（简化：使用第一个账户）
        IntArrayList accounts = positionManager.getAccountsByBalance(quoteAssetId, priceE8 * qtyE8 / 100_000_000L);
        if (accounts.isEmpty()) {
            return; // 余额不足
        }
        
        int accountId = accounts.getInt(0);
        
        // 预扣余额
        long requiredQuoteE8 = priceE8 * qtyE8 / 100_000_000L;
        if (!positionManager.reserve(accountId, quoteAssetId, requiredQuoteE8)) {
            return; // 余额不足
        }
        
        // 创建订单命令
        OrderCommand cmd = new OrderCommand();
        cmd.accountId = (short) accountId;
        cmd.symbolId = targetSymbolId;
        cmd.exchangeId = targetExchangeId;
        cmd.priceE8 = priceE8;
        cmd.qtyE8 = qtyE8;
        cmd.side = 0; // Buy
        
        // 提交订单
        oms.submitOrder(cmd);
        
        // 更新当前订单信息
        currentBidOrderId = cmd.internalOrderId;
    }
    
    /**
     * 提交卖单。
     */
    private void submitAskOrder(long priceE8, long qtyE8) {
        // 获取账户（简化：使用第一个账户）
        IntArrayList accounts = positionManager.getAccountsByBalance(baseAssetId, qtyE8);
        if (accounts.isEmpty()) {
            return; // 余额不足
        }
        
        int accountId = accounts.getInt(0);
        
        // 预扣余额
        if (!positionManager.reserve(accountId, baseAssetId, qtyE8)) {
            return; // 余额不足
        }
        
        // 创建订单命令
        OrderCommand cmd = new OrderCommand();
        cmd.accountId = (short) accountId;
        cmd.symbolId = targetSymbolId;
        cmd.exchangeId = targetExchangeId;
        cmd.priceE8 = priceE8;
        cmd.qtyE8 = qtyE8;
        cmd.side = 1; // Sell
        
        // 提交订单
        oms.submitOrder(cmd);
        
        // 更新当前订单信息
        currentAskOrderId = cmd.internalOrderId;
    }
    
    /**
     * 计算订单数量（根据币安深度）。
     */
    private long calculateOrderQty(long binanceDepthE8) {
        long qtyE8 = (long) (binanceDepthE8 * DEPTH_PERCENTAGE);
        
        // 限制在最小值和最大值之间
        if (qtyE8 < MIN_ORDER_SIZE_E8) {
            qtyE8 = MIN_ORDER_SIZE_E8;
        } else if (qtyE8 > MAX_ORDER_SIZE_E8) {
            qtyE8 = MAX_ORDER_SIZE_E8;
        }
        
        return qtyE8;
    }
}

