package com.xinyue.maker.core.oms;

import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.OrderCommand;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.common.SymbolRegistry;
import com.xinyue.maker.core.gateway.ExecutionGateway;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.infra.MetricsService;
import com.xinyue.maker.infra.PersistenceDispatcher;
import com.xinyue.maker.io.output.ExecutionGatewayManager;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongArrayList;

import java.util.concurrent.atomic.AtomicLong;


/**
 * 通过原始类型索引追踪订单全生命周期。
 * <p>
 * 索引结构：
 * 1. 全局主索引：LongObjectMap<Order> (localOrderId -> Order) - 极速查找任意订单
 * 2. 外部ID索引：Long2LongHashMap (exchangeOrderId -> localOrderId) - 交易所回报时快速定位
 * 3. 分账户索引：Int2ObjectHashMap<LongArrayList> (accountId -> List<localOrderId>) - 按账户查询
 * 4. 价格索引：Long2ObjectHashMap<LongArrayList> (price -> List<localOrderId>) - 按价格查询
 */
public final class OrderManagementSystem {

    private final MetricsService metricsService;
    private final ExecutionGatewayManager gatewayManager;
    private final PositionManager positionManager;

    // === 订单ID生成器 ===
    private final AtomicLong orderIdGenerator = new AtomicLong(1); // TODO: 提交下单路径需要时再启用

    // === 索引结构（Zero GC） ===
    
    // 2.1 全局主索引：localOrderId -> Order（O(1) 查找）
    private final Long2ObjectHashMap<Order> globalIndex = new Long2ObjectHashMap<>();
    
    // 2.2 外部ID索引：exchangeOrderId -> localOrderId（交易所回报时快速定位）
    private final Long2LongHashMap externalIndex = new Long2LongHashMap(-1);

    // 2.2.1 ClientID 索引：已废弃（当前约定：clientId == localOrderId，直接 globalIndex.get(clientId)）
    
    // 2.3 分账户索引：accountId -> List<localOrderId>
    private final Int2ObjectHashMap<LongArrayList> accountIndex = new Int2ObjectHashMap<>();
    
    // 价格索引：price -> List<localOrderId>（买单和卖单分开）
    private final Long2ObjectHashMap<LongArrayList> bidPriceIndex = new Long2ObjectHashMap<>();
    private final Long2ObjectHashMap<LongArrayList> askPriceIndex = new Long2ObjectHashMap<>();

    public OrderManagementSystem(MetricsService metricsService,
                                 PersistenceDispatcher persistenceDispatcher,
                                 ExecutionGatewayManager gatewayManager,
                                 PositionManager positionManager) {
        this.metricsService = metricsService;
        this.gatewayManager = gatewayManager;
        this.positionManager = positionManager;
    }
    
    /**
     * 获取 ExecutionGatewayManager（供 AccountBalanceBalancer 使用）。
     */
    public ExecutionGatewayManager getGatewayManager() {
        return gatewayManager;
    }

    /**
     * 提交订单到交易所。
     * 策略层调用此方法下单，OMS 负责创建订单并加入索引。
     */
    public void submitOrder(OrderCommand command) {
        // 1. 生成内部订单ID
       long localOrderId = orderIdGenerator.getAndIncrement();
        // long localOrderId = System.currentTimeMillis()-Long.valueOf("1765000000000");
        // 2. 创建订单对象（TODO: 从对象池获取，实现零GC）
        Order order = new Order();
        order.localOrderId = localOrderId;
        order.accountId = command.accountId;
        order.symbolId = command.symbolId;
        order.exchangeId = command.exchangeId;
        order.priceE8 = command.priceE8;
        order.qtyE8 = command.qtyE8;
        // 统一订单方向编码：OrderCommand 和 Order 都使用 0=Buy, 1=Sell
        order.side = (byte) command.side; // 直接赋值，编码一致
        order.orderType = 1; // Limit
        order.orderStatus = 1; // Created
        order.createTime = System.currentTimeMillis();
        
        // 3. 预扣余额（在加入索引前检查，避免索引污染）
        if (!reserveBalanceForOrder(command, order)) {
            order.orderStatus = 7; // Rejected
            // 不加入索引，直接返回
            return;
        }
        
        // 4. 加入全局主索引
        globalIndex.put(localOrderId, order);
        
        // 5. 加入分账户索引
        LongArrayList accountOrders = accountIndex.computeIfAbsent(command.accountId, k -> new LongArrayList());
        accountOrders.add(localOrderId);
        
        // 6. 加入价格索引
        if (command.side == 0) { // Buy
            LongArrayList priceOrders = bidPriceIndex.computeIfAbsent(command.priceE8, k -> new LongArrayList());
            priceOrders.add(localOrderId);
        } else if (command.side == 1) { // Sell
            LongArrayList priceOrders = askPriceIndex.computeIfAbsent(command.priceE8, k -> new LongArrayList());
            priceOrders.add(localOrderId);
        }
        
        // 7. 更新 OrderCommand 的 internalOrderId
        command.internalOrderId = localOrderId;
        
        // 7.1 将 clientId 加入索引（clientId 通常就是 internalOrderId）
        
        // 8. 记录指标
        metricsService.recordOrder(command.symbolId);

        // 9. 根据 exchangeId 获取对应的 ExecutionGateway
        Exchange exchange = Exchange.fromId(command.exchangeId);
        ExecutionGateway gateway = gatewayManager.getGateway(exchange);
        if (gateway == null) {
            // TODO: 记录错误日志或触发告警
            order.orderStatus = 7; // Rejected
            // 余额已在前面扣减，如果网关不可用，需要释放余额
            releaseBalanceForRejectedOrder(order);
            return;
        }
        
        // 10. 通过 ExecutionGateway 异步发送（非阻塞，< 5us）
        order.submitTime = System.currentTimeMillis();
        order.orderStatus = 2; // PendingNew
        gateway.sendOrder(command);
    }



    /**
     * 3.1 查询价格区间内的买单数量。
     *
     * @param minPriceE8 最小价格（放大 1e8）
     * @param maxPriceE8 最大价格（放大 1e8）
     * @return 订单数量
     */
    public int countBidOrdersInPriceRange(long minPriceE8, long maxPriceE8) {
        final int[] count = {0}; // 使用数组包装以在 lambda 中使用
        // 遍历价格索引（注意：Long2ObjectHashMap 的遍历可能不是最优的，但对于小规模数据足够快）
        bidPriceIndex.forEach((price, orderIds) -> {
            if (price >= minPriceE8 && price <= maxPriceE8) {
                // 只统计活跃订单
                for (int i = 0; i < orderIds.size(); i++) {
                    long localOrderId = orderIds.getLong(i);
                    Order order = globalIndex.get(localOrderId);
                    if (order != null && order.isActive()) {
                        count[0]++;
                    }
                }
            }
        });
        return count[0];
    }

    /**
     * 3.2 查询买一价上是否有挂单。
     *
     * @param bestBidE8 买一价（放大 1e8）
     * @return true 如果有挂单，false 否则
     */
    public boolean hasOrderAtBestBid(long bestBidE8) {
        LongArrayList orderIds = bidPriceIndex.get(bestBidE8);
        if (orderIds == null || orderIds.isEmpty()) {
            return false;
        }
        
        // 检查是否有活跃订单
        for (int i = 0; i < orderIds.size(); i++) {
            long localOrderId = orderIds.getLong(i);
            Order order = globalIndex.get(localOrderId);
            if (order != null && order.isActive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查指定账户在指定价格和方向上是否有活跃订单。
     * 用于避免自成交：买单时检查该账户是否在 bestAsk 上有卖单，卖单时检查该账户是否在 bestBid 上有买单。
     *
     * @param accountId 账户 ID
     * @param priceE8 价格（放大 1e8）
     * @param side 订单方向（0=Buy, 1=Sell）
     * @param symbolId 交易对 ID（用于过滤）
     * @return true 如果该账户在该价格和方向上有活跃订单，false 否则
     */
    public boolean hasAccountOrderAtPrice(short accountId, long priceE8, byte side, short symbolId) {
        // 根据方向选择对应的价格索引
        Long2ObjectHashMap<LongArrayList> priceIndex = (side == 0) ? bidPriceIndex : askPriceIndex;
        
        LongArrayList orderIds = priceIndex.get(priceE8);
        if (orderIds == null || orderIds.isEmpty()) {
            return false;
        }
        
        // 检查是否有该账户的活跃订单
        for (int i = 0; i < orderIds.size(); i++) {
            long localOrderId = orderIds.getLong(i);
            Order order = globalIndex.get(localOrderId);
            if (order != null 
                && order.isActive() 
                && order.accountId == accountId 
                && order.symbolId == symbolId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据本地订单ID获取订单对象。
     */
    public Order getOrder(long localOrderId) {
        return globalIndex.get(localOrderId);
    }

    /**
     * 根据交易所订单ID获取订单对象。
     */
    public Order getOrderByExchangeId(long exchangeOrderId) {
        long localOrderId = externalIndex.get(exchangeOrderId);
        if (localOrderId == -1) {
            return null;
        }
        return globalIndex.get(localOrderId);
    }

    /**
     * 根据 clientId 获取订单对象。
     * 用于账户订单更新时匹配订单。
     */
    public Order getOrderByClientId(long clientId) {
        return globalIndex.get(clientId);
    }

    /**
     * 处理账户订单更新（从 dYdX v4_subaccounts 频道接收）。
     * 通过 clientId 匹配订单并更新状态。
     */
    public void onAccountOrderUpdate(CoreEvent event) {
        // 判断是否是同步订单（message_id == 1，firstUpdateId == -1 表示同步）
        boolean isSync = (event.firstUpdateId == -1);
        
        if (isSync) {
            // 同步模式：这是启动时或断联重连后的「重建本地订单视图」
            // 直接根据事件中的数组重建本地订单，不依赖历史索引
            for (int i = 0; i < event.orderCount; i++) {
                long clientId = event.orderClientIds[i];
                if (clientId == 0) {
                    continue;
                }

                long localOrderId = clientId; // 约定：dYdX 的 clientId 直接作为本地订单ID
                // 创建新订单对象（TODO: 改为从对象池获取）
                Order order = new Order();
                order.localOrderId = localOrderId;
                order.accountId = event.accountId;          // 账户映射完成后，这里会被正确填充
                order.symbolId = event.orderSymbolIds[i];
                order.exchangeId = event.exchangeId;
                order.priceE8 = event.orderPrices[i];
                order.qtyE8 = event.orderQtys[i];
                order.filledQtyE8 = event.orderFilledQtys[i];
                order.side = event.orderSides[i];
                order.orderType = 1; // Limit
                order.orderStatus = event.orderStatuses[i];
                // dYdX v4 cancel 需要的字段（从同步数组重建）
                order.clobPairId = event.orderClobPairIds[i];
                order.orderFlags = event.orderFlags[i];
                order.goodTilBlockTimeSec = event.orderGoodTilBlockTimeSec[i];
                long now = System.currentTimeMillis();
                order.createTime = now;
                order.updateTime = now;

                // 写入全局主索引（重建时直接覆盖同 ID 的旧订单）
                globalIndex.put(localOrderId, order);

                // 分账户索引
                if (order.accountId > 0) {
                    LongArrayList accountOrders = accountIndex.computeIfAbsent(order.accountId, k -> new LongArrayList());
                    accountOrders.add(localOrderId);
                }

                // 价格索引（根据买卖方向分别挂入 bid/ask）
                if (order.side == 0) { // Buy
                    LongArrayList priceOrders = bidPriceIndex.computeIfAbsent(order.priceE8, k -> new LongArrayList());
                    priceOrders.add(localOrderId);
                } else if (order.side == 1) { // Sell
                    LongArrayList priceOrders = askPriceIndex.computeIfAbsent(order.priceE8, k -> new LongArrayList());
                    priceOrders.add(localOrderId);
                }

                // 指标统计
                metricsService.recordOrder(order.symbolId);
            }
            System.out.println("sync order successed");
        } else {
            // 增量更新模式：只处理单个订单（clientId 存储在 event.clientOidHash 中）
            long clientId = event.clientOidHash;
            Order order = getOrderByClientId(clientId);
            
            if (order == null) {
                return;
            }
            
            // 更新订单状态和成交数量
            order.updateTime = System.currentTimeMillis();
            byte oldStatus = order.orderStatus;
            order.orderStatus = event.orderStatus;

            // 更新 dYdX v4 cancel 字段（增量消息可能携带）
            if (event.clobPairId != 0) {
                order.clobPairId = event.clobPairId;
            }
            if (event.orderFlag != 0) {
                order.orderFlags = event.orderFlag;
            }
            if (event.goodTilBlockTimeSec != 0L) {
                order.goodTilBlockTimeSec = event.goodTilBlockTimeSec;
            }
            
            // 更新成交数量
            if (event.filledQty > 0) {
                order.filledQtyE8 = event.filledQty;
            }
            
            // 如果订单被取消，释放余额（撤单确认后释放）
            if (order.orderStatus == 6 && oldStatus != 6) { // Canceled（状态刚变为 Canceled）
                releaseBalanceForCanceledOrder(order);
            }
            
            // 如果订单被取消或完全成交，从价格索引中移除
            if (order.orderStatus == 6 || order.orderStatus == 5) { // Canceled or Filled
                removeOrderFromPriceIndex(order);
            }
            
            // 记录指标
            metricsService.recordOrder(order.symbolId);
        }
    }

    /**
     * 取消订单（异步，非阻塞）。
     * 
     * @param localOrderId 本地订单ID
     */
    public void cancelOrder(long localOrderId) {
        Order order = globalIndex.get(localOrderId);
        if (order == null) {
            return; // 订单不存在
        }
        
        // 如果订单已经完成或取消，不需要再取消
        if (order.orderStatus == 5 || order.orderStatus == 6) { // Filled or Canceled
            return;
        }
        
        // 立即将订单状态标记为 PendingCancel（状态码 8）
        order.orderStatus = 8; // PendingCancel
        order.updateTime = System.currentTimeMillis();
        
        // 从价格索引移除该订单（避免查询到，但不释放余额）
        removeOrderFromPriceIndex(order);
        
        // 异步发送撤单请求到交易所（非阻塞）
        Exchange exchange = Exchange.fromId(order.exchangeId);
        ExecutionGateway gateway = gatewayManager.getGateway(exchange);
        if (gateway != null) {
            OrderCommand cancelCommand = new OrderCommand();
            cancelCommand.accountId = order.accountId;
            cancelCommand.internalOrderId = order.localOrderId;
            cancelCommand.orderFlags = order.orderFlags;
            cancelCommand.clobPairId = order.clobPairId;
            cancelCommand.goodTilBlockTimeSec = order.goodTilBlockTimeSec;

             gateway.sendOrder(cancelCommand);
        }
        
        // 不释放余额，等撤单确认消息（ACCOUNT_ORDER_UPDATE 或 EXECUTION_REPORT）到来时再释放
    }
    
    /**
     * 从价格索引中移除订单。
     */
    private void removeOrderFromPriceIndex(Order order) {
        Long2ObjectHashMap<LongArrayList> priceIndex = (order.side == 0) ? bidPriceIndex : askPriceIndex;
        LongArrayList orderIds = priceIndex.get(order.priceE8);
        if (orderIds != null) {
            // 移除订单ID（需要遍历查找）
            for (int i = orderIds.size() - 1; i >= 0; i--) {
                if (orderIds.getLong(i) == order.localOrderId) {
                    orderIds.remove(i);
                    break;
                }
            }
            // 如果该价格档位没有订单了，可以移除（可选优化）
            if (orderIds.isEmpty()) {
                priceIndex.remove(order.priceE8);
            }
        }
    }
    
    /**
     * 预扣订单所需余额。
     * 买单：扣减报价资产（USDT），数量 = price * qty / 1e8
     * 卖单：扣减基础资产（BTC），数量 = qty
     * 
     * @param command 订单命令（OrderCommand.side: 0=Buy, 1=Sell）
     * @param order 订单对象（Order.side: 0=Buy, 1=Sell）
     * @return true 扣减成功，false 余额不足
     */
    private boolean reserveBalanceForOrder(OrderCommand command, Order order) {
        if (positionManager == null) {
            return true; // PositionManager 未注入，跳过余额检查
        }
        
        // 从 symbolId 推导基础资产和报价资产
        AssetPair assetPair = parseAssetPair(command.symbolId);
        if (assetPair == null) {
            return true; // 无法推导资产类型，跳过余额检查
        }
        
        // OrderCommand 和 Order 都使用 0=Buy, 1=Sell
        if (command.side == 0) { // Buy
            // 买单：扣减报价资产（USDT），数量 = price * qty / 1e8
            // 使用安全计算避免溢出
            long requiredQuoteE8 = multiplyAndDivideSafe(command.priceE8, command.qtyE8, ScaleConstants.SCALE_E8);
            return positionManager.reserve(command.accountId, assetPair.quoteAssetId, requiredQuoteE8);
        } else if (command.side == 1) { // Sell
            // 卖单：扣减基础资产（BTC），数量 = qty
            return positionManager.reserve(command.accountId, assetPair.baseAssetId, command.qtyE8);
        }
        
        return true; // 未知方向，跳过余额检查
    }
    
    /**
     * 安全计算 (a * b) / divisor，避免溢出。
     * 方法：将 a 和 b 分别分解为高位和低位，使用展开公式避免溢出。
     * 对于 divisor = 1e8，使用 (aDiv * 1e4 + aRem) * (bDiv * 1e4 + bRem) / 1e8 的展开形式。
     * 
     * @param a 被乘数（放大 1e8）
     * @param b 被乘数（放大 1e8）
     * @param divisor 除数（通常是 ScaleConstants.SCALE_E8，即 1e8）
     * @return (a * b) / divisor（放大 1e8）
     */
    private static long multiplyAndDivideSafe(long a, long b, long divisor) {
        // 将 a 和 b 分别分解为高位和低位：a = aDiv * 10000 + aRem, b = bDiv * 10000 + bRem
        // (a * b) / 1e8 = (aDiv * 1e4 + aRem) * (bDiv * 1e4 + bRem) / 1e8
        // = (aDiv * bDiv * 1e8 + aDiv * bRem * 1e4 + aRem * bDiv * 1e4 + aRem * bRem) / 1e8
        // = aDiv * bDiv + (aDiv * bRem + aRem * bDiv) / 1e4 + (aRem * bRem) / 1e8
        long aDiv = a / 10_000L;
        long aRem = a % 10_000L;
        long bDiv = b / 10_000L;
        long bRem = b % 10_000L;
        
        long result = aDiv * bDiv; // 主要项：aDiv * bDiv * 1e8 / 1e8 = aDiv * bDiv（不会溢出）
        
        // 次要项：(aDiv * bRem + aRem * bDiv) / 1e4
        // aDiv * bRem 和 aRem * bDiv 的最大值约为 1e14 * 1e4 = 1e18，在 long 范围内
        result += (aDiv * bRem + aRem * bDiv) / 10_000L;
        
        // 最后一项：(aRem * bRem) / 1e8，最大值约为 1e8，加上以提高精度
        result += (aRem * bRem) / ScaleConstants.SCALE_E8;
        
        return result;
    }
    
    /**
     * 释放被拒绝订单的余额（下单失败时回滚预扣的余额）。
     * Order.side 使用标准编码：0=Buy, 1=Sell
     */
    private void releaseBalanceForRejectedOrder(Order order) {
        if (positionManager == null) {
            return;
        }
        
        AssetPair assetPair = parseAssetPair(order.symbolId);
        if (assetPair == null) {
            return;
        }
        
        if (order.side == 0) { // Buy
            // 买单：释放报价资产
            long releaseAmountE8 = multiplyAndDivideSafe(order.priceE8, order.qtyE8, ScaleConstants.SCALE_E8);
            positionManager.release(order.accountId, assetPair.quoteAssetId, releaseAmountE8);
        } else if (order.side == 1) { // Sell
            // 卖单：释放基础资产
            positionManager.release(order.accountId, assetPair.baseAssetId, order.qtyE8);
        }
    }
    
    /**
     * 从交易对 symbolId 解析出基础资产和报价资产的 assetId。
     * 
     * @param symbolId 交易对ID
     * @return 资产对（baseAssetId, quoteAssetId），如果无法解析则返回 null
     */
    private AssetPair parseAssetPair(short symbolId) {
        SymbolRegistry symbolRegistry = SymbolRegistry.getInstance();
        AssetRegistry assetRegistry = AssetRegistry.getInstance();
        
        String symbol = symbolRegistry.getSymbol(symbolId);
        if (symbol == null) {
            return null;
        }
        
        // 简单规则：假设交易对格式为 BASEQUOTE（如 BTCUSDT）
        // TODO: 更通用的解析逻辑，支持其他格式（如 ETHBTC, BNBUSDC）
        short baseAssetId = 0;
        short quoteAssetId = 0;
        
        if (symbol.endsWith("USDT")) {
            String baseSymbol = symbol.substring(0, symbol.length() - 4);
            baseAssetId = assetRegistry.get(baseSymbol);
            quoteAssetId = assetRegistry.get("USDT");
        }
        
        if (baseAssetId == 0 || quoteAssetId == 0) {
            return null;
        }
        
        return new AssetPair(baseAssetId, quoteAssetId);
    }
    
    /**
     * 资产对（基础资产和报价资产的 assetId）。
     */
    private static class AssetPair {
        final short baseAssetId;
        final short quoteAssetId;
        
        AssetPair(short baseAssetId, short quoteAssetId) {
            this.baseAssetId = baseAssetId;
            this.quoteAssetId = quoteAssetId;
        }
    }
    
    /**
     * 释放已取消订单的余额。
     * 买单：释放报价资产（USDT），数量 = price * remainingQty
     * 卖单：释放基础资产（BTC），数量 = remainingQty
     */
    private void releaseBalanceForCanceledOrder(Order order) {
        if (positionManager == null) {
            return; // PositionManager 未注入，跳过
        }
        
        AssetPair assetPair = parseAssetPair(order.symbolId);
        if (assetPair == null) {
            return; // 无法推导资产类型
        }
        
        long remainingQtyE8 = order.getRemainingQtyE8();
        if (remainingQtyE8 <= 0) {
            return; // 没有剩余数量，无需释放
        }
        
        if (order.side == 0) { // Buy
            // 买单：释放报价资产（USDT）
            long releaseAmountE8 = multiplyAndDivideSafe(order.priceE8, remainingQtyE8, ScaleConstants.SCALE_E8);
            positionManager.release(order.accountId, assetPair.quoteAssetId, releaseAmountE8);
        } else if (order.side == 1) { // Sell
            // 卖单：释放基础资产（BTC）
            positionManager.release(order.accountId, assetPair.baseAssetId, remainingQtyE8);
        }
    }
}

