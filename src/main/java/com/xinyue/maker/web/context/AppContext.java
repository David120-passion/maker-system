package com.xinyue.maker.web.context;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.config.AccountConfig;
import com.xinyue.maker.core.CoreEngine;
import com.xinyue.maker.core.CoreEventFactory;
import com.xinyue.maker.core.CoreEventHandler;
import com.xinyue.maker.core.lob.LobManager;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.infra.MetricsService;
import com.xinyue.maker.infra.PersistenceDispatcher;
import com.xinyue.maker.io.input.AccessLayerCoordinator;
import com.xinyue.maker.io.input.GapDetector;
import com.xinyue.maker.io.input.dydx.DydxMarketDataConnector;
import com.xinyue.maker.io.output.DydxConnector;
import com.xinyue.maker.io.output.ExecutionGatewayManager;
import com.xinyue.maker.io.output.NettySidecarGateway;
import com.xinyue.maker.strategy.ExecutionRouter;
import com.xinyue.maker.strategy.RiskEngine;
import com.xinyue.maker.strategy.SignalGenerator;
import com.xinyue.maker.strategy.StrategyEngine;
import com.xinyue.maker.web.service.StrategyService;
import org.agrona.collections.Int2ObjectHashMap;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * 应用上下文管理器。
 * 负责初始化和管理系统的核心组件。
 */
@Component
public class AppContext {

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private CoreEngine coreEngine;
    private StrategyEngine strategyEngine;
    private OrderManagementSystem oms;
    private PositionManager positionManager;
    private LobManager lobManager;
    private RingBuffer<CoreEvent> ringBuffer;
    private StrategyService strategyService;
    private DydxMarketDataConnector dydxConnector;
    private NettySidecarGateway dydxGateway;
    private AccessLayerCoordinator accessLayerCoordinator;

    @Init
    public void init() {
        LOG.info("正在初始化应用上下文...");
        
        try {
            // L4 基础设施层
            MetricsService metricsService = new MetricsService();
            PersistenceDispatcher persistenceDispatcher = new PersistenceDispatcher();

            // 启动 dYdX Sidecar 连接器
            DydxConnector dydxSidecarConnector = new DydxConnector("ws://127.0.0.1:8080");
            dydxSidecarConnector.start();

            // 创建 Disruptor
            Disruptor<CoreEvent> disruptor = createDisruptor();

            // 创建 Normalizer 和 AccessLayerCoordinator
            com.xinyue.maker.io.Normalizer normalizer = new com.xinyue.maker.io.Normalizer(disruptor.getRingBuffer());
            com.xinyue.maker.infra.OriginalMessageDao originalMessageDao = null;
            try {
                originalMessageDao = new com.xinyue.maker.infra.OriginalMessageDao();
            } catch (Exception e) {
//                LOG.warn("无法初始化 OriginalMessageDao", e);
            }
            accessLayerCoordinator = new AccessLayerCoordinator();
            dydxConnector = new DydxMarketDataConnector(normalizer, originalMessageDao);
            accessLayerCoordinator.register(dydxConnector);

            // 创建 PositionManager
            positionManager = new PositionManager(metricsService);

            // 创建 ExecutionGateway
            dydxGateway = createDydxGateway(dydxSidecarConnector);
            ExecutionGatewayManager gatewayManager = new ExecutionGatewayManager()
                    .register(com.xinyue.maker.common.Exchange.DYDX, dydxGateway);

            // 创建 OMS
            oms = new OrderManagementSystem(metricsService, persistenceDispatcher, gatewayManager, positionManager);

            // 创建 LobManager
            lobManager = new LobManager(metricsService);

            // 创建 GapDetector
            GapDetector gapDetector = new GapDetector(lobManager, accessLayerCoordinator);

            // 创建 StrategyEngine
            strategyEngine = createStrategyEngine(oms, positionManager, metricsService);

            // 初始化账户（TradeSession + 资产余额），但不订阅订单（订单订阅在启动策略时进行）
//            configureAllAccounts(dydxGateway, positionManager);

            // 创建 CoreEventHandler
            CoreEventHandler coreEventHandler = new CoreEventHandler(
                    lobManager,
                    oms,
                    positionManager,
                    strategyEngine,
                    persistenceDispatcher,
                    gapDetector,
                    accessLayerCoordinator
            );
            disruptor.handleEventsWith(coreEventHandler);

            // 创建 CoreEngine
            coreEngine = new CoreEngine(disruptor, gapDetector, accessLayerCoordinator, null);

            // 启动 CoreEngine
            coreEngine.start();

            // 获取 RingBuffer
            ringBuffer = disruptor.getRingBuffer();

            // 创建 StrategyService
            strategyService = new StrategyService(
                    strategyEngine,
                    oms,
                    positionManager,
                    lobManager,
                    ringBuffer
            );

            LOG.info("应用上下文初始化完成");
        } catch (Exception e) {
            LOG.error("应用上下文初始化失败", e);
            throw new RuntimeException("应用上下文初始化失败", e);
        }
    }

    private Disruptor<CoreEvent> createDisruptor() {
        ThreadFactory threadFactory = Thread.ofVirtual().name("disruptor-", 0).factory();
        return CoreEngine.bootstrapDisruptor(
                new CoreEventFactory(),
                null,
                threadFactory
        );
    }

    private NettySidecarGateway createDydxGateway(DydxConnector dydxSidecarConnector) {
        Int2ObjectHashMap<com.xinyue.maker.io.output.TradeSession> dydxSessionPool = new Int2ObjectHashMap<>();
        return new NettySidecarGateway(
                dydxSidecarConnector.getChannel(),
                dydxSessionPool,
                com.xinyue.maker.common.Exchange.DYDX
        );
    }

    private StrategyEngine createStrategyEngine(OrderManagementSystem oms,
                                               PositionManager positionManager,
                                               MetricsService metricsService) {
        SignalGenerator signalGenerator = new SignalGenerator();
        ExecutionRouter executionRouter = new ExecutionRouter(oms, positionManager);
        RiskEngine riskEngine = new RiskEngine(positionManager, metricsService);
        return new StrategyEngine(signalGenerator, executionRouter, riskEngine);
    }

    // Getters
    public StrategyService getStrategyService() {
        return strategyService;
    }

    public StrategyEngine getStrategyEngine() {
        return strategyEngine;
    }

    public OrderManagementSystem getOms() {
        return oms;
    }

    public PositionManager getPositionManager() {
        return positionManager;
    }

    public LobManager getLobManager() {
        return lobManager;
    }

    public CoreEngine getCoreEngine() {
        return coreEngine;
    }

    public DydxMarketDataConnector getDydxConnector() {
        return dydxConnector;
    }

    public NettySidecarGateway getDydxGateway() {
        return dydxGateway;
    }

    /**
     * 统一配置所有账户：从配置文件读取账户信息，初始化 TradeSession 和资产余额。
     * 注意：订单订阅不在初始化时进行，而是在启动策略时根据 buyAccountIds 和 sellAccountIds 订阅。
     * 这是阻塞操作，所有账户初始化完成后才会返回。
     */
    private static void configureAllAccounts(NettySidecarGateway dydxGateway, PositionManager positionManager) {
        // 从配置文件读取所有账户信息
        List<AccountConfig.AccountInfo> accounts = AccountConfig.loadAccounts();

        if (accounts.isEmpty()) {
            throw new RuntimeException("警告: 未找到任何账户配置，请检查 accounts.properties 文件");
        }

        System.out.println("从配置文件加载了 " + accounts.size() + " 个账户，开始初始化...");

        // 为每个账户初始化 TradeSession、订单订阅和资产余额（阻塞操作）
        for (AccountConfig.AccountInfo account : accounts) {
            try {
                // 1. 初始化 TradeSession（用于下单）
                dydxGateway.initializeSession(
                        account.accountId,
                        account.accountName,
                        account.mnemonicPhrase
                );

                // 2. 初始化账户资产余额（阻塞操作，从 dYdX REST API 同步）
                // 注意：账户订单订阅不在初始化时进行，而是在启动策略时根据 buyAccountIds 和 sellAccountIds 订阅
                System.out.println(String.format(
                        "正在初始化账户资产: accountId=%d, address=%s...",
                        account.accountId, account.address
                ));
                positionManager.initializeFromDydx(account.accountId, account.address, account.subaccountNumber);

                System.out.println(String.format(
                        "账户配置成功: accountId=%d, accountName=%s, address=%s, subaccountNumber=%d",
                        account.accountId, account.accountName, account.address, account.subaccountNumber
                ));
            } catch (Exception e) {
                System.err.println(String.format(
                        "账户配置失败: accountId=%d, address=%s, error=%s",
                        account.accountId, account.address, e.getMessage()
                ));
                e.printStackTrace();
                // 继续处理下一个账户，不中断整个初始化流程
            }
        }

        System.out.println("all account init finish！");
    }
}

