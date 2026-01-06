package com.xinyue.maker.core;

import com.lmax.disruptor.EventHandler;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.core.lob.LobManager;
import com.xinyue.maker.core.lob.ILocalOrderBook;
import com.xinyue.maker.core.lob.OrderBookSnapshot;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.io.input.AccessLayerCoordinator;
import com.xinyue.maker.io.input.GapDetector;
import com.xinyue.maker.strategy.StrategyEngine;
import com.xinyue.maker.infra.PersistenceDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表示 L2 整条单线程热路径的事件处理器。
 */
public final class CoreEventHandler implements EventHandler<CoreEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(LobManager.class);


    private final LobManager lobManager;
    private final OrderManagementSystem oms;
    private final PositionManager positionManager;
    private final StrategyEngine strategyEngine;
    private final PersistenceDispatcher persistenceDispatcher;
    private final GapDetector gapDetector;
    private final AccessLayerCoordinator accessLayerCoordinator;

    // dYdX：按 symbolId 记录上一次处理的 message_id，用于 gap 检测
    // 使用简单的数组即可（symbolId 为 short）
    private final long[] dydxLastMessageId = new long[Short.MAX_VALUE];
    
    // dYdX：标记哪些 symbol 正在重建订单簿（重建期间忽略增量更新）
    private final boolean[] dydxRebuilding = new boolean[Short.MAX_VALUE];

    public CoreEventHandler(LobManager lobManager,
                            OrderManagementSystem oms,
                            PositionManager positionManager,
                            StrategyEngine strategyEngine,
                            PersistenceDispatcher persistenceDispatcher,
                            GapDetector gapDetector,
                            AccessLayerCoordinator accessLayerCoordinator) {
        this.lobManager = lobManager;
        this.oms = oms;
        this.positionManager = positionManager;
        this.strategyEngine = strategyEngine;
        this.persistenceDispatcher = persistenceDispatcher;
        this.gapDetector = gapDetector;
        this.accessLayerCoordinator = accessLayerCoordinator;
    }

    @Override
    public void onEvent(CoreEvent event, long sequence, boolean endOfBatch) {
        try {
            switch (event.type) {
                case MARKET_DATA_TICK -> handleMarketData(event);
                case DEPTH_UPDATE -> handleDepthUpdate(event);
//                case EXECUTION_REPORT -> handleExecution(event);
                case ACCOUNT_ORDER_UPDATE -> handleAccountOrderUpdate(event);
                case STRATEGY_COMMAND -> handleStrategyCommand(event);
                case CONFIG_UPDATE -> handleConfigUpdate(event);
//                case TIMER -> strategyEngine.onTimer(event);
                case TEST -> strategyEngine.onTimer(event); // TEST 事件也调用 onTimer
                default -> {
                }
            }
            persistenceDispatcher.publish(event);
        } catch (Throwable t) {
            LOG.error("错误",t);
            strategyEngine.killSwitch();
        } finally {
            event.reset();
        }
    }

    private void handleMarketData(CoreEvent event) {
        lobManager.onMarketData(event);
        strategyEngine.onMarketData(event, lobManager.primaryReference());
    }

    private void handleDepthUpdate(CoreEvent event) {
        long currentTimeMillis = System.currentTimeMillis();

        Exchange exchange = Exchange.fromId(event.exchangeId);
        
        // Binance 需要 GapDetector 处理（快照+缓冲对齐）
        if (exchange == Exchange.BINANCE) {
            if (!gapDetector.onDepthUpdateEvent(event)) {
                return;
            }
        }
        // dYdX 直接处理（全量+增量）
        else if (exchange == Exchange.DYDX) {
            handleDydxDepthUpdate(event);
        }else if(exchange == Exchange.Test){
            gapDetector.onTestDepthUpdateEvent(event);
        }
        
        // 同时也可以作为普通行情事件处理（如果需要）
        lobManager.onMarketData(event);
        
        // 策略层执行：如果有做市策略，调用策略的 onDepthUpdate
        if (exchange == Exchange.BINANCE) {
            // 获取参考订单簿快照（Binance）
            OrderBookSnapshot referenceSnapshot = lobManager.referenceSnapshot(Exchange.BINANCE);
            strategyEngine.onDepthUpdate(event, referenceSnapshot);
        } else if (exchange == Exchange.DYDX){
            strategyEngine.onDepthUpdate(event);
        }else if(exchange == Exchange.Test){
            OrderBookSnapshot referenceSnapshot = lobManager.referenceSnapshot(Exchange.Test);
            strategyEngine.onDepthUpdate(event, referenceSnapshot);
        }
//        LOG.info("策略执行完毕总耗时:"+(System.currentTimeMillis() - currentTimeMillis));
    }

    private void handleDydxDepthUpdate(CoreEvent event) {
        Exchange exchange = Exchange.fromId(event.exchangeId);
        short symbolId = event.symbolId;
        long messageId = event.sequence; // 在 Normalizer 中已写入 dYdX 的 message_id
        ILocalOrderBook orderBook = lobManager.getOrderBook(exchange, symbolId);

        // 如果正在重建中，只处理全量快照（subscribed 消息），忽略增量更新（channel_data）
        if (dydxRebuilding[symbolId]) {
            // 全量快照的特征：firstUpdateId == -1（在 Normalizer 中标记）
            if (event.firstUpdateId == -1) {
                System.out.println("rebuild completed");
                // 收到新的全量快照，重建完成
                dydxRebuilding[symbolId] = false;
                if (messageId != 0) {
                    dydxLastMessageId[symbolId] = messageId;
                }
                
                // 计算实际的买盘和卖盘数量
                int bidCount = 0;
                int askCount = 0;
                for (int i = 0; i < event.depthCount && i < com.xinyue.maker.common.CoreEvent.MAX_DEPTH; i++) {
                    if (event.bidPrices[i] != 0 || event.bidQtys[i] != 0) {
                        bidCount = i + 1;
                    }
                    if (event.askPrices[i] != 0 || event.askQtys[i] != 0) {
                        askCount = i + 1;
                    }
                }
                
                orderBook.applyDydxSnapshot(
                        event.bidPrices, event.bidQtys, bidCount,
                        event.askPrices, event.askQtys, askCount
                );
                lobManager.syncFromLocalOrderBook(exchange, symbolId, orderBook);
            }
            // 重建期间忽略增量更新（firstUpdateId == 0 表示 channel_data）
            return;
        }

        // 1. 通过判断 messageId 是否连续，检测 gap
//        long last = dydxLastMessageId[symbolId];
////        if(last == 100)last = 100+1;
//        if (last != 0 && messageId != 0 && messageId != last + 1) {
//            // 2. 检测到 gap：清空本地订单簿并标记为重建中
//            System.out.println("checked gap,start rebuild");
//            orderBook.reset();
//            dydxLastMessageId[symbolId] = 0L;
//            dydxRebuilding[symbolId] = true; // 标记为重建中
//
//            // 3/4/5. 调用接入层执行「取消订阅 + 重新订阅」
//            String symbolStr = SymbolRegistry.getInstance().getSymbol(symbolId);
//            if (symbolStr != null) {
//                String dydxSymbol = toDydxSymbol(symbolStr);
//                accessLayerCoordinator.resubscribeOrderBook(exchange, dydxSymbol);
//            }
//            // 这条增量消息直接丢弃
//            return;
//        }

        // 更新 last messageId（忽略 messageId 为 0 的情况）
        if (messageId != 0) {
            dydxLastMessageId[symbolId] = messageId;
        }

        // 计算实际的买盘和卖盘数量
        int bidCount = 0;
        int askCount = 0;
        for (int i = 0; i < event.depthCount && i < com.xinyue.maker.common.CoreEvent.MAX_DEPTH; i++) {
            if (event.bidPrices[i] != 0 || event.bidQtys[i] != 0) {
                bidCount = i + 1;
            }
            if (event.askPrices[i] != 0 || event.askQtys[i] != 0) {
                askCount = i + 1;
            }
        }

        // 判断是全量快照还是增量更新：
        // - firstUpdateId == -1 表示全量快照（subscribed 消息）
        // - firstUpdateId == 0 表示增量更新（channel_data 消息）
        if (event.firstUpdateId == -1) {
            // 全量快照
            orderBook.applyDydxSnapshot(
                    event.bidPrices, event.bidQtys, bidCount,
                    event.askPrices, event.askQtys, askCount
            );
        } else {
            // 增量更新
            orderBook.applyDydxIncrementalUpdate(
                    event.bidPrices, event.bidQtys, bidCount,
                    event.askPrices, event.askQtys, askCount
            );
        }

        // 同步 bestBid/bestAsk
        lobManager.syncFromLocalOrderBook(exchange, symbolId, orderBook);
    }

    private String toDydxSymbol(String symbol) {
        // 简单规则：BTCUSDT -> BTC-USDT，其它保持原样
        if (symbol.endsWith("USDT") && symbol.length() > 5) {
            String base = symbol.substring(0, symbol.length() - 4);
            return base + "-USDT";
        }
        return symbol;
    }

    private void handleExecution(CoreEvent event) {
//        oms.onExecution(event);
        positionManager.onExecution(event);
        strategyEngine.onExecution(event);
    }

    private void handleAccountOrderUpdate(CoreEvent event) {
        // 处理账户订单更新（从 dYdX v4_subaccounts 频道接收）
        oms.onAccountOrderUpdate(event);
        
        // 处理余额更新（如果有 assetPositions 数据）
        if (event.assetCount > 0 && event.accountId == 1) {
            positionManager.updateBalances(event.accountId, event.assetIds, event.assetBalances, event.assetCount);
        }
        
        // 处理转账（如果有 transfers 数据）
        if (event.transferAssetId > 0 && event.transferAmountE8 > 0) {
            boolean isTransferIn = (event.transferType == 0);
            positionManager.processTransfer(event.accountId, event.transferAssetId, event.transferAmountE8, isTransferIn);
        }
        
        // 调用策略层的 onAccountOrderUpdate（用于处理撤单确认等异步操作）
        strategyEngine.onAccountOrderUpdate(event);
    }

    private void handleStrategyCommand(CoreEvent event) {
        strategyEngine.onCommand(event);
    }

    private void handleConfigUpdate(CoreEvent event) {
        strategyEngine.onConfig(event);
    }
}

