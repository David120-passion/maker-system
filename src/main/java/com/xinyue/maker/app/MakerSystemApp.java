package com.xinyue.maker.app;

import com.lmax.disruptor.dsl.Disruptor;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.config.DynamicConfigService;
import com.xinyue.maker.core.CoreEngine;
import com.xinyue.maker.core.CoreEventFactory;
import com.xinyue.maker.core.CoreEventHandler;
import com.xinyue.maker.core.lob.LobManager;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.infra.MetricsService;
import com.xinyue.maker.infra.PersistenceDispatcher;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.io.AccessLayerCoordinator;
import com.xinyue.maker.io.BinanceMarketDataConnector;
import com.xinyue.maker.io.GapDetector;
import com.xinyue.maker.io.ListenKeyRefresher;
import com.xinyue.maker.io.Normalizer;
import com.xinyue.maker.io.SessionManager;
import com.xinyue.maker.io.TargetExchangeMarketDataConnector;
import com.xinyue.maker.strategy.ExecutionRouter;
import com.xinyue.maker.strategy.RiskEngine;
import com.xinyue.maker.strategy.SignalGenerator;
import com.xinyue.maker.strategy.StrategyEngine;

import java.util.concurrent.ThreadFactory;

/**
 * 启动四层做市系统。
 */
public final class MakerSystemApp {

    public static void main(String[] args) {
        // L4 基础设施层
        MetricsService metricsService = new MetricsService();
        PersistenceDispatcher persistenceDispatcher = new PersistenceDispatcher();
        DynamicConfigService configService = new DynamicConfigService();

        // L2 核心层
        LobManager lobManager = new LobManager(metricsService);
        OrderManagementSystem oms = new OrderManagementSystem(metricsService, persistenceDispatcher);
        PositionManager positionManager = new PositionManager(metricsService);

        StrategyEngine strategyEngine = wireStrategyLayer(oms, positionManager, metricsService);
        CoreEventHandler coreEventHandler = new CoreEventHandler(
                lobManager,
                oms,
                positionManager,
                strategyEngine,
                persistenceDispatcher
        );

        ThreadFactory threadFactory = Thread.ofVirtual().name("disruptor-", 0).factory();
        Disruptor<CoreEvent> disruptor = CoreEngine.bootstrapDisruptor(
                new CoreEventFactory(),
                coreEventHandler,
                threadFactory
        );

        // L1 接入层
        SessionManager sessionManager = new SessionManager(configService);
        Normalizer normalizer = new Normalizer(disruptor.getRingBuffer());
        AccessLayerCoordinator accessLayerCoordinator = new AccessLayerCoordinator()
                .register(new BinanceMarketDataConnector(normalizer))
                .register(new TargetExchangeMarketDataConnector(Exchange.TARGET, normalizer));
        GapDetector gapDetector = new GapDetector(lobManager, accessLayerCoordinator);
        ListenKeyRefresher listenKeyRefresher = new ListenKeyRefresher(sessionManager);

        CoreEngine engine = new CoreEngine(disruptor, gapDetector, accessLayerCoordinator, listenKeyRefresher);
        engine.start();
    }

    private static StrategyEngine wireStrategyLayer(OrderManagementSystem oms,
                                                    PositionManager positionManager,
                                                    MetricsService metricsService) {
        // L3 策略层
        SignalGenerator signalGenerator = new SignalGenerator();
        ExecutionRouter executionRouter = new ExecutionRouter(oms, positionManager);
        RiskEngine riskEngine = new RiskEngine(positionManager, metricsService);
        return new StrategyEngine(signalGenerator, executionRouter, riskEngine);
    }
}

