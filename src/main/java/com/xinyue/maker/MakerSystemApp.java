package com.xinyue.maker;

import com.lmax.disruptor.dsl.Disruptor;
import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.config.AccountConfig;
import com.xinyue.maker.config.AccountConfig.AccountInfo;
import com.xinyue.maker.config.DynamicConfigService;
import com.xinyue.maker.core.CoreEngine;
import com.xinyue.maker.core.CoreEventFactory;
import com.xinyue.maker.core.CoreEventHandler;
import com.xinyue.maker.common.CoreEventType;
import com.xinyue.maker.core.lob.LobManager;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.infra.MetricsService;
import com.xinyue.maker.infra.OriginalMessageDao;
import com.xinyue.maker.infra.PersistenceDispatcher;
import com.xinyue.maker.io.ListenKeyRefresher;
import com.xinyue.maker.io.Normalizer;
import com.xinyue.maker.io.SessionManager;
import com.xinyue.maker.io.input.AccessLayerCoordinator;
import com.xinyue.maker.io.input.GapDetector;
import com.xinyue.maker.io.input.dydx.DydxMarketDataConnector;
import com.xinyue.maker.io.output.DydxConnector;
import com.xinyue.maker.io.output.ExecutionGatewayManager;
import com.xinyue.maker.io.output.NettySidecarGateway;
import com.xinyue.maker.io.output.TradeSession;
import com.xinyue.maker.strategy.*;
import org.agrona.collections.Int2ObjectHashMap;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动四层做市系统。
 */
public final class MakerSystemApp {
    //订单簿流程测试 下单 账户订单维护情况
    //1 启动下单 每秒下一笔订单看下
    //2. 看看订单簿的维护情况
    public static void main(String[] args) {
        // 修复 IDEA 控制台中文乱码问题：设置 UTF-8 编码
        fixConsoleEncoding();

        // L4 基础设施层
        MetricsService metricsService = new MetricsService();
        //未知
        PersistenceDispatcher persistenceDispatcher = new PersistenceDispatcher();

        DydxConnector dydxSidecarConnector = new DydxConnector("ws://127.0.0.1:8080");
        dydxSidecarConnector.start();

        //队列
        Disruptor<CoreEvent> disruptor = getCoreEventDisruptor();

        //转换器
        Normalizer normalizer = new Normalizer(disruptor.getRingBuffer());
        //落库处理
        OriginalMessageDao originalMessageDao =null;
        try {
            originalMessageDao = new OriginalMessageDao();
        }catch (Exception e){

        }
        AccessLayerCoordinator accessLayerCoordinator = new AccessLayerCoordinator()
                .register(new DydxMarketDataConnector(normalizer,originalMessageDao));

        //多账户资产管理（需要先创建，因为 OMS 需要它来释放余额）
        PositionManager positionManager = new PositionManager(metricsService);
        
        //账户订单管理（需要先创建连接器）
        NettySidecarGateway dydxGateway = createDydxGateway(dydxSidecarConnector);
        OrderManagementSystem oms = getOrderManagementSystem(dydxGateway, metricsService, persistenceDispatcher, positionManager);

        //dydx行情 账户订单变动配置：统一配置所有账户（TradeSession + 订单订阅 + 资产初始化）  需要抽象成所有交易所公用
        dydxConnectorConfigAndStart(accessLayerCoordinator, dydxGateway, positionManager);

        //策略引擎
        StrategyEngine strategyEngine = wireStrategyLayer(oms, positionManager, metricsService);

        // L2 核心层
        LobManager lobManager = new LobManager(metricsService);
        //缺口检测器  需优化 抽象出来
        GapDetector gapDetector = new GapDetector(lobManager, accessLayerCoordinator);
        //核心任务处理器
        CoreEventHandler coreEventHandler = getCoreEventHandler(persistenceDispatcher, lobManager, oms, positionManager, strategyEngine, accessLayerCoordinator, gapDetector);
        disruptor.handleEventsWith(coreEventHandler);
        // 设置 Disruptor 的 handler（需要在创建后设置）
        //todo
        DynamicConfigService configService = new DynamicConfigService();
        // todo
        SessionManager sessionManager = new SessionManager(configService);
        //todo
        ListenKeyRefresher listenKeyRefresher = new ListenKeyRefresher(sessionManager);

        CoreEngine engine = new CoreEngine(disruptor, gapDetector, accessLayerCoordinator, listenKeyRefresher);

//        MarketMakingStrategy marketMakingStrategy = new PriceFollowingStrategy(oms,positionManager,(short) 2,"ETH","USDT",Exchange.DYDX);
//        MarketMakingStrategy marketMakingStrategy = new SlowOrderTestStrategy(oms,positionManager);
        
        // 创建 InternalRangeOscillatorStrategy：账户分工（账户 1 专门挂买单，账户 2 专门挂卖单）
        AssetRegistry assetRegistry = AssetRegistry.getInstance();
        
        // 配置参数
        long minPriceE8 = 30 * ScaleConstants.SCALE_E8;              // $10.00
        long maxPriceE8 = 45 * ScaleConstants.SCALE_E8;              // $15.00
        // 价格区间：$10.00 - $15.00，中点 $12.50，价差 $5.00
        long tickSizeE8 = 1000000;                                   // $0.01（交易所规定的最小变动单位，占价差的0.2%）
        // 波动率百分比：相对于价格区间中点的标准差
        // 对于 $10.00-$15.00（中点 $12.50），设置为 1.5% 表示实际波动 ≈ $0.1875
        // 这个波动率在价差 $5.00 的范围内是合理的（约占价差的 3.75%）

        long cycleDurationMs = (long)(3 * 3600.0 * 1000.0);                     // 1 小时
        // 目标量：500 ETH（保持原有设置）
        // 注意：最小订单量是 0.01 个，500 ETH 的目标量是合理的
        long targetVolumeE8 = 5000L * ScaleConstants.SCALE_E8;       // 500 ETH
        long triggerIntervalMs = 3000L;                              // 触发间隔：6 秒（与 TestEventScheduler 保持一致）
        boolean enableVolumeTarget = true;                           // 启用目标量控制
        int makerCounts = 6;                                         // 每次上涨或下跌挂单数量

        double volatilityPercent = 3;                             // 1.5%（适用于中高价格区间）
        double noiseFactory = 0.5;                               //噪声因子
        
        // 下单间隔配置（策略内部判断是否需要下单）
        long minOrderIntervalMs = 3000L;  // 最小下单间隔：3秒
        long maxOrderIntervalMs = 6000L;  // 最大下单间隔：6秒
        
        InternalRangeOscillatorStrategy2 marketMakingStrategy = new InternalRangeOscillatorStrategy2(
            oms,
            positionManager,
            lobManager,                          // lobManager: 用于读取 dYdX 订单簿
            new short[]{(short) 1},              // buyAccountIds: 账户 1 专门挂买单
            new short[]{(short) 2},              // sellAccountIds: 账户 2 专门挂卖单
            minPriceE8,                          // minPriceE8: $1.00
            maxPriceE8,                          // maxPriceE8: $1.50
            tickSizeE8,                          // tickSizeE8: $0.01（交易所规定）
            volatilityPercent,                   // volatilityPercent: 3.0%（相对于价格区间中点的百分比）
            (short) 5,                           // symbolId: 2 (ETHUSDT)
            assetRegistry.get("H2"),            // baseAssetId: ETH
            assetRegistry.get("USDT"),           // quoteAssetId: USDT
            Exchange.DYDX.id(),                  // exchangeId: DYDX
            cycleDurationMs,                      // cycleDurationMs: 1 小时
            targetVolumeE8,                      // targetVolumeE8: 500 ETH
            triggerIntervalMs,                   // triggerIntervalMs: 6 秒（用于动态计算订单数量范围）
            enableVolumeTarget,                   // enableVolumeTarget: 启用目标量控制
            makerCounts,                           // 每次上涨或下跌 需要制造的订单数量 上涨就在卖一下面挂买单   下跌就在买一上方挂卖单  保持订单簿充足
            noiseFactory,                        // noiseFactory: 噪声因子
            minOrderIntervalMs,                  // minOrderIntervalMs: 最小下单间隔
            maxOrderIntervalMs,                  // maxOrderIntervalMs: 最大下单间隔
            0.7,                                 // progressThreshold: 上涨/下跌周期分界点（0.0 ~ 1.0），默认 0.7
            0.015,                               // minCorrectionIntervalRatio: 最小回调间隔比例，默认 0.015（1.5%）
            0.08,                                // maxCorrectionIntervalRatio: 最大回调间隔比例，默认 0.08（8%）
            2.0,                                 // minCorrectionAmplitudePercent: 最小回调幅度，默认 2.0
            6.0,                                 // maxCorrectionAmplitudePercent: 最大回调幅度，默认 6.0
            0.017,                               // minCorrectionDurationRatio: 最小回调持续时间比例，默认 0.017（1.7%）
            0.04,                                // maxCorrectionDurationRatio: 最大回调持续时间比例，默认 0.04（4%）
            5.0                                  // convergenceThresholdPercent: 收敛阈值，默认 5.0
        );
        strategyEngine.setMarketMakingStrategy(  assetRegistry.get("H2"),marketMakingStrategy);

        engine.start();

        // 启动全局统一事件源（每秒发送一次事件，所有策略共享）
//        startGlobalTimer(disruptor.getRingBuffer());
        
//         如果使用了 InternalRangeOscillatorStrategy，启动账户资产均衡器
//        if (currentStrategy instanceof InternalRangeOscillatorStrategy) {
//            InternalRangeOscillatorStrategy strategy = (InternalRangeOscillatorStrategy) currentStrategy;
//            ExecutionGatewayManager gatewayManager = oms.getGatewayManager(); // 需要从 OMS 获取 gatewayManager
//            startAccountBalanceBalancer(
//                    strategy.getBuyAccountIds(),
//                    strategy.getSellAccountIds(),
//                    strategy.getQuoteAssetId(),
//                    strategy.getBaseAssetId(),
//                    strategy.getExchangeId(),
//                    positionManager,
//                    gatewayManager
//            );
//        }
    }
    
//    /**
//     * 启动账户资产均衡器。
//     */
//    private static void startAccountBalanceBalancer(
//            short[] buyAccountIds,
//            short[] sellAccountIds,
//            short quoteAssetId,
//            short baseAssetId,
//            short exchangeId,
//            PositionManager positionManager,
//            ExecutionGatewayManager gatewayManager) {
//
//        // 配置参数
//        long minQuoteBalanceE8 = 1000L * ScaleConstants.SCALE_E8;  // 最小 USDT 余额：1000 USDT
//        long minBaseBalanceE8 = 1L * ScaleConstants.SCALE_E8;     // 最小现货余额：1 ETH/BTC
//        long transferAmountE8 = 500L * ScaleConstants.SCALE_E8;   // 每次转移数量：500 USDT 或 0.5 ETH/BTC
//
//        // 创建并启动均衡器
//        AccountBalanceBalancer balancer = new AccountBalanceBalancer(
//                buyAccountIds,
//                sellAccountIds,
//                quoteAssetId,
//                baseAssetId,
//                exchangeId,
//                minQuoteBalanceE8,
//                minBaseBalanceE8,
//                transferAmountE8,
//                positionManager,
//                gatewayManager
//        );
//
//        balancer.start();
//        System.out.println("账户资产均衡器已启动: minQuoteBalance=" + minQuoteBalanceE8 / 1_0000_0000.0 +
//                ", minBaseBalance=" + minBaseBalanceE8 / 1_0000_0000.0 +
//                ", transferAmount=" + transferAmountE8 / 1_0000_0000.0);
//    }

    private static Disruptor<CoreEvent> getCoreEventDisruptor() {
        ThreadFactory threadFactory = Thread.ofVirtual().name("disruptor-", 0).factory();

        // 先创建 Disruptor（但先不启动），因为 Normalizer 需要 RingBuffer
        Disruptor<CoreEvent> disruptor = CoreEngine.bootstrapDisruptor(
                new CoreEventFactory(),
                null, // 先传 null，后面再设置 handler
                threadFactory
        );
        return disruptor;
    }

    private static NettySidecarGateway createDydxGateway(DydxConnector dydxSidecarConnector) {
        // 创建 dYdX 的会话池（可以根据实际账户数量调整）
        Int2ObjectHashMap<TradeSession> dydxSessionPool = new Int2ObjectHashMap<>();

        // 创建 dYdX 的 ExecutionGateway
        return new NettySidecarGateway(
                dydxSidecarConnector.getChannel(),
                dydxSessionPool,
                Exchange.DYDX
        );
    }

    private static OrderManagementSystem getOrderManagementSystem(NettySidecarGateway dydxGateway, MetricsService metricsService, PersistenceDispatcher persistenceDispatcher, PositionManager positionManager) {
        // 注册到 ExecutionGatewayManager
        ExecutionGatewayManager gatewayManager = new ExecutionGatewayManager()
                .register(Exchange.DYDX, dydxGateway);

        // 创建 OMS，传入 gatewayManager 和 positionManager
        OrderManagementSystem oms = new OrderManagementSystem(metricsService, persistenceDispatcher, gatewayManager, positionManager);
        return oms;
    }

    /**
     * 统一配置所有账户：从配置文件读取账户信息，初始化 TradeSession、账户订单订阅和资产余额。
     * 这是阻塞操作，所有账户初始化完成后才会返回。
     */
    private static void configureAllAccounts(NettySidecarGateway dydxGateway, DydxMarketDataConnector dydxConnector, PositionManager positionManager) {
        // 从配置文件读取所有账户信息
        List<AccountInfo> accounts = AccountConfig.loadAccounts();
        
        if (accounts.isEmpty()) {
            throw new RuntimeException("警告: 未找到任何账户配置，请检查 accounts.properties 文件");
        }
        
        System.out.println("从配置文件加载了 " + accounts.size() + " 个账户，开始初始化...");
        
        // 为每个账户初始化 TradeSession、订单订阅和资产余额（阻塞操作）
        for (AccountInfo account : accounts) {
        try {
                // 1. 初始化 TradeSession（用于下单）
                dydxGateway.initializeSession(
                    account.accountId,
                    account.accountName,
                    account.mnemonicPhrase
                );
                
                // 2. 配置账户订单订阅（用于接收订单更新）
                dydxConnector.configureAccountOrders(account.address, account.subaccountNumber);

                // 3. 初始化账户资产余额（阻塞操作，从 dYdX REST API 同步）
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

    private static void dydxConnectorConfigAndStart(AccessLayerCoordinator accessLayerCoordinator, NettySidecarGateway dydxGateway, PositionManager positionManager) {
        // 获取 dYdX 连接器
        DydxMarketDataConnector dydxConnector = (DydxMarketDataConnector) accessLayerCoordinator.getConnector(Exchange.DYDX);
        if (dydxConnector == null) {
//            throw new RuntimeException("not found dydx connector");
            return;
        }
        
        // 统一配置所有账户（TradeSession + 订单订阅 + 资产初始化，阻塞操作）
        configureAllAccounts(dydxGateway, dydxConnector, positionManager);
    }

    private static CoreEventHandler getCoreEventHandler(PersistenceDispatcher persistenceDispatcher, LobManager lobManager, OrderManagementSystem oms, PositionManager positionManager, StrategyEngine strategyEngine, AccessLayerCoordinator accessLayerCoordinator, GapDetector gapDetector) {
        CoreEventHandler coreEventHandler = new CoreEventHandler(
                lobManager,
                oms,
                positionManager,
                strategyEngine,
                persistenceDispatcher,
                gapDetector,
                accessLayerCoordinator
        );
        return coreEventHandler;
    }

    private static StrategyEngine wireStrategyLayer(OrderManagementSystem oms,
                                                    PositionManager positionManager,
                                                    MetricsService metricsService) {
        // L3 策略层
        SignalGenerator signalGenerator = new SignalGenerator();
        ExecutionRouter executionRouter = new ExecutionRouter(oms, positionManager);
        RiskEngine riskEngine = new RiskEngine(positionManager, metricsService);
        StrategyEngine strategyEngine = new StrategyEngine(signalGenerator, executionRouter, riskEngine);

        return strategyEngine;
    }

    /**
     * 修复 IDEA 控制台中文乱码问题。
     * 强制设置 System.out 和 System.err 使用 UTF-8 编码。
     */
    private static void fixConsoleEncoding() {
        try {
            // 设置系统属性（部分 JVM 需要）
            System.setProperty("file.encoding", "UTF-8");
            System.setProperty("sun.jnu.encoding", "UTF-8");
            
            // 强制设置 System.out 和 System.err 为 UTF-8 编码
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 如果设置失败，不影响程序运行，只是可能还有乱码
            System.err.println("Warning: Failed to set console encoding to UTF-8: " + e.getMessage());
        }
    }
    
    /**
     * 全局统一事件源（所有策略共享）。
     */
    private static ScheduledExecutorService globalTimer;
    private static final AtomicBoolean timerRunning = new AtomicBoolean(false);
    
    /**
     * 启动全局统一事件源（每秒发送一次事件，所有策略共享）。
     * 与 StrategyService 中的实现保持一致。
     * 
     * @param ringBuffer Disruptor RingBuffer
     */
    private static void startGlobalTimer(com.lmax.disruptor.RingBuffer<CoreEvent> ringBuffer) {
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
                    System.err.println("全局定时器发送事件失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 0, 1, TimeUnit.SECONDS);
            
            System.out.println("全局统一事件源已启动（每秒发送一次事件）");
        }
    }
}

