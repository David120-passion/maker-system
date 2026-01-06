package com.xinyue.maker.strategy;

import com.xinyue.maker.common.*;
import com.xinyue.maker.core.lob.OrderBookSnapshot;
import com.xinyue.maker.core.oms.Order;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 慢速订单测试策略：用于核对订单簿和资产。
 * 
 * 策略流程：
 * 1. 下单（买单/卖单）
 * 2. 等待订单确认（New 状态）
 * 3. 等待一段时间（可配置，默认 5 秒）
 * 4. 撤单
 * 5. 等待撤单确认（Canceled 状态）
 * 6. 等待一段时间（可配置，默认 3 秒）
 * 7. 循环回到步骤 1
 * 
 * 每次状态变化时，都会打印详细的订单簿和资产信息，方便核对。
 */
public final class SlowOrderTestStrategy implements MarketMakingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(SlowOrderTestStrategy.class);

    /**
     * 测试状态枚举。
     */
    private enum TestState {
        IDLE,           // 空闲，准备下单
        PLACING,        // 正在下单（已调用 submitOrder，等待 New 确认）
        PLACED,         // 订单已确认（New 状态），等待一段时间后撤单
        CANCELING,      // 正在撤单（已调用 cancelOrder，等待 Canceled 确认）
        CANCELED        // 撤单已确认，等待一段时间后重新下单
    }

    // === 配置参数 ===
    private final int accountId = 1; // TODO: 从配置读取
    private final short symbolId = SymbolRegistry.getInstance().get("ETHUSDT"); // TODO: 从配置读取
    private final short baseAssetId = AssetRegistry.getInstance().get("ETH"); // TODO: 从配置读取
    private final short quoteAssetId = AssetRegistry.getInstance().get("USDT"); // TODO: 从配置读取
    
    // 测试订单参数
    private final long basePriceE8 = 20_0000000000L; // 基础价格 20 USDT（放大 1e8）
    private final long priceStepE8 = 10_0000000L; // 价格步长 0.1 USDT（放大 1e8）
    private final long testQtyE8 = 1_00000000L; // 0.01 eth（放大 1e8）
    
    // 等待时间（毫秒）
    private final long waitAfterPlaceMs = 5000L; // 下单后等待 30 秒
    private final long waitAfterCancelMs = 5000L; // 撤单后等待 30 秒
    
    // === 依赖组件 ===
    private final OrderManagementSystem oms;
    private final PositionManager positionManager;
    
    // === 策略状态 ===
    private TestState state = TestState.IDLE;
    private long currentOrderId = 0;
    private byte currentSide = 0; // 0=Buy, 1=Sell（交替）
    private long stateEnterTime = 0; // 进入当前状态的时间戳
    
    // 价格计数器（买单递减，卖单递增）
    private long buyPriceCounter = 0;  // 买单价格 = basePriceE8 - buyPriceCounter * priceStepE8
    private long sellPriceCounter = 0;  // 卖单价格 = basePriceE8 + sellPriceCounter * priceStepE8
    
    public SlowOrderTestStrategy(OrderManagementSystem oms, PositionManager positionManager) {
        this.oms = oms;
        this.positionManager = positionManager;
    }

    @Override
    public void onDepthUpdate(CoreEvent event, OrderBookSnapshot referenceSnapshot) {
        // 测试策略不在深度更新中触发，改为在 TEST 事件中触发
    }

    @Override
    public void onAccountOrderUpdate(CoreEvent event) {
        // 只处理我们自己的订单
        if (event.clientOidHash != currentOrderId) {
            return;
        }
        
        Order order = oms.getOrder(currentOrderId);
        if (order == null) {
            LOG.warn("订单不存在: orderId={}", currentOrderId);
            return;
        }
        
        byte newStatus = event.orderStatus;
        
        switch (state) {
            case PLACING:
                // 等待订单确认（New 状态，status = 3）
                if (newStatus == 3) { // New
                    LOG.info("========== 订单已确认 ==========");
                    printOrderBookAndAssets("订单确认后");
                    state = TestState.PLACED;
                    stateEnterTime = System.currentTimeMillis();
                    LOG.info("状态转换: PLACING -> PLACED，等待 {}ms 后撤单", waitAfterPlaceMs);
                }
                break;
                
            case CANCELING:
                // 等待撤单确认（Canceled 状态，status = 6）
                if (newStatus == 6) { // Canceled
                    LOG.info("========== 撤单已确认 ==========");
                    printOrderBookAndAssets("撤单确认后");
                    state = TestState.CANCELED;
                    stateEnterTime = System.currentTimeMillis();
                    currentOrderId = 0; // 清空当前订单ID
                    LOG.info("状态转换: CANCELING -> CANCELED，等待 {}ms 后重新下单", waitAfterCancelMs);
                }
                break;
                
            default:
                // 其他状态不处理
                break;
        }
    }
    
    /**
     * 下单。
     */
    private void placeOrder() {
        if (state != TestState.IDLE && state != TestState.CANCELED) {
            return;
        }
        
        // 计算价格（买单递减，卖单递增）
        long orderPriceE8;
        if (currentSide == 0) { // Buy
            orderPriceE8 = basePriceE8 - buyPriceCounter * priceStepE8;
        } else { // Sell
            orderPriceE8 = basePriceE8 + sellPriceCounter * priceStepE8;
        }
        
        // 检查余额
        long requiredQuote = orderPriceE8 * testQtyE8 / 1_0000_0000L; // 需要的报价资产（USDT）
        long requiredBase = testQtyE8; // 需要的基础资产（BTC）
        
        long availableQuote = positionManager.getFreeBalance(accountId, quoteAssetId);
        long availableBase = positionManager.getFreeBalance(accountId, baseAssetId);
        
        if (currentSide == 0) { // Buy
            if (availableQuote < requiredQuote) {
                LOG.warn("余额不足，无法下单买单: 需要 {} USDT，可用 {} USDT", 
                    requiredQuote / 1_0000_0000L, availableQuote / 1_0000_0000L);
                return;
            }
        } else { // Sell
            if (availableBase < requiredBase) {
                LOG.warn("余额不足，无法下单卖单: 需要 {} BTC，可用 {} BTC", 
                    requiredBase / 1_0000_0000L, availableBase / 1_0000_0000L);
                return;
            }
        }
        
        // 创建订单命令
        OrderCommand command = new OrderCommand();
        command.accountId = (short) accountId;
        command.symbolId = symbolId;
        command.exchangeId = Exchange.DYDX.id();
        command.priceE8 = orderPriceE8;
        command.qtyE8 = testQtyE8;
        command.side = (short) currentSide;
        
        LOG.info("========== 开始下单 ==========");
        LOG.info("订单参数: side={}, price={}, qty={}, buyCounter={}, sellCounter={}", 
            currentSide == 0 ? "BUY" : "SELL",
            orderPriceE8 / 1_0000_0000L,
            testQtyE8 / 1_0000_0000L,
            buyPriceCounter,
            sellPriceCounter);
        
        printOrderBookAndAssets("下单前");
        
        // 提交订单
        oms.submitOrder(command);
        currentOrderId = command.internalOrderId;
        state = TestState.PLACING;
        stateEnterTime = System.currentTimeMillis();
        
        // 更新价格计数器（订单提交成功后）
        if (currentSide == 0) { // Buy
            buyPriceCounter++;
        } else { // Sell
            sellPriceCounter++;
        }
        
        LOG.info("订单已提交: orderId={}, 状态转换: {} -> PLACING", currentOrderId, state == TestState.IDLE ? "IDLE" : "CANCELED");
        
        // 切换买卖方向（下次交替）：0(Buy) <-> 1(Sell)
        currentSide = (byte) (currentSide == 0 ? 1 : 0);
    }
    
    /**
     * 检查并执行撤单（在 PLACED 状态等待一段时间后调用）。
     */
    public void checkAndCancel() {
        if (state != TestState.PLACED) {
            return;
        }
        
        long now = System.currentTimeMillis();
        if ((now - stateEnterTime) < waitAfterPlaceMs) {
            return; // 还没到撤单时间
        }
        
        LOG.info("========== 开始撤单 ==========");
        printOrderBookAndAssets("撤单前");
        
        oms.cancelOrder(currentOrderId);
        state = TestState.CANCELING;
        stateEnterTime = System.currentTimeMillis();
        
        LOG.info("撤单请求已发送: orderId={}, 状态转换: PLACED -> CANCELING", currentOrderId);
    }
    
    /**
     * 检查并执行重新下单（在 CANCELED 状态等待一段时间后调用）。
     */
    public void checkAndRePlace() {
        if (state != TestState.CANCELED) {
            return;
        }
        
        long now = System.currentTimeMillis();
        if ((now - stateEnterTime) < waitAfterCancelMs) {
            return; // 还没到重新下单时间
        }
        
        state = TestState.IDLE;
        stateEnterTime = System.currentTimeMillis();
        // 下次 onDepthUpdate 时会触发下单
    }
    
    /**
     * 打印订单簿和资产信息（用于核对）。
     */
    private void printOrderBookAndAssets(String context) {
        LOG.info("---------- {} 订单簿和资产信息 ----------", context);
        
        // 1. 打印当前订单信息
        if (currentOrderId != 0) {
            Order order = oms.getOrder(currentOrderId);
            if (order != null) {
                LOG.info("当前订单: orderId={}, side={}, price={}, qty={}, filledQty={}, status={}", 
                    order.localOrderId,
                    order.side == 0 ? "BUY" : "SELL",
                    order.priceE8 / 1_0000_0000L,
                    order.qtyE8 / 1_0000_0000L,
                    order.filledQtyE8 / 1_0000_0000L,
                    getStatusString(order.orderStatus));
            }
        }
        
        // 2. 打印账户资产信息
        long freeUsdt = positionManager.getFreeBalance(accountId, quoteAssetId);
        long lockedUsdt = positionManager.getLockedBalance(accountId, quoteAssetId);
        long freeBtc = positionManager.getFreeBalance(accountId, baseAssetId);
        long lockedBtc = positionManager.getLockedBalance(accountId, baseAssetId);
        
        LOG.info("账户资产 (accountId={}):", accountId);
        LOG.info("  USDT: free={}, locked={}, total={}", 
            freeUsdt / 1_0000_0000L, 
            lockedUsdt / 1_0000_0000L,
            (freeUsdt + lockedUsdt) / 1_0000_0000L);
        LOG.info("  ETH:  free={}, locked={}, total={}",
            freeBtc / 1_0000_0000L, 
            lockedBtc / 1_0000_0000L,
            (freeBtc + lockedBtc) / 1_0000_0000L);
        
        // 3. 打印账户所有订单（简单统计）
        printAccountOrdersSummary();
        
        LOG.info("----------------------------------------");
    }
    
    /**
     * 打印账户订单摘要。
     */
    private void printAccountOrdersSummary() {
        // 通过 OMS 的 accountIndex 获取账户订单（需要添加访问方法）
        // 这里暂时只打印当前订单信息
        if (currentOrderId != 0) {
            Order order = oms.getOrder(currentOrderId);
            if (order != null) {
                LOG.info("账户订单数: 1 (当前订单: orderId={}, status={})", 
                    currentOrderId, getStatusString(order.orderStatus));
            }
        } else {
            LOG.info("账户订单数: 0");
        }
    }
    
    /**
     * 获取订单状态字符串。
     */
    private String getStatusString(byte status) {
        switch (status) {
            case 1: return "Created";
            case 2: return "PendingNew";
            case 3: return "New";
            case 4: return "PartiallyFilled";
            case 5: return "Filled";
            case 6: return "Canceled";
            case 7: return "Rejected";
            case 8: return "PendingCancel";
            default: return "Unknown(" + status + ")";
        }
    }
    
    /**
     * 定时检查（由 StrategyEngine.onTimer 或 TEST 事件调用）。
     * 用于在 PLACED 和 CANCELED 状态时检查是否到了执行下一步的时间。
     */
    @Override
    public void onTimer(CoreEvent event) {
        LOG.debug("onTimer 被调用，当前状态: {}", state);
        
        // 检查是否需要撤单
        checkAndCancel();
        
        // 检查是否需要重新下单
        checkAndRePlace();
        
        // 在 IDLE 或 CANCELED 状态时，如果到了时间就下单
        if (state == TestState.IDLE || state == TestState.CANCELED) {
            long now = System.currentTimeMillis();
            if (stateEnterTime == 0 || (now - stateEnterTime) >= waitAfterCancelMs) {
                LOG.info("触发下单，当前状态: {}, stateEnterTime: {}, now: {}", state, stateEnterTime, now);
                placeOrder();
            }
        }
    }

}

