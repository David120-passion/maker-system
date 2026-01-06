package com.xinyue.maker.web.controller;

import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.common.SymbolRegistry;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.io.input.dydx.DydxMarketDataConnector;
import com.xinyue.maker.io.output.NettySidecarGateway;
import com.xinyue.maker.web.context.AppContext;
import com.xinyue.maker.web.model.AccountInfo;
import com.xinyue.maker.web.service.AccountConfigService;
import com.xinyue.maker.web.service.StrategyService;
import com.xinyue.maker.web.service.TokenManager;
import com.xinyue.maker.web.service.UserService;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略管理控制器。
 * 提供策略的启动、停止和状态查询接口。
 */
@Controller
@Mapping("/api/strategy")
public class StrategyController {

    private static final Logger LOG = LoggerFactory.getLogger(StrategyController.class);
    
    @Inject
    private AppContext appContext;
    
    @Inject
    private TokenManager tokenManager;
    
    @Inject
    private AccountConfigService accountService;
    
    @Inject
    private UserService userService;
    
    private StrategyService getStrategyService() {
        return appContext.getStrategyService();
    }

    /**
     * 启动策略请求 VO。
     */
    public static class StartStrategyRequest {
        // 账户配置
        public int[] buyAccountIds;
        public int[] sellAccountIds;
        
        // 价格配置（前端传普通数值）
        public Double minPrice;
        public Double maxPrice;
        public Double tickSize;
        
        // 策略参数
        public Double volatilityPercent;
        public Short symbolId;
        public String baseAssetId;
        public Short exchangeId;
        
        // 时间配置（前端传秒）
        public Long cycleDuration;  // 周期时长（秒）
        public Double targetVolume; // 目标量
        public Long triggerInterval; // 触发间隔（秒）
        
        // 其他配置
        public Boolean enableVolumeTarget;
        public Integer makerCounts;
        public Double noiseFactory;
        
        // 事件调度器配置（可选，前端传秒）
        public Long minInterval; // 最小下单间隔（秒）
        public Long maxInterval; // 最大下单间隔（秒）
        
        // 策略周期配置（可选）
        public Double progressThreshold; // 上涨/下跌周期分界点（0.0 ~ 1.0），默认 0.7
        
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
     * 启动策略。
     * POST /api/strategy/start
     * 
     * 请求头：Authorization: Bearer {token}
     */
    @Post
    @Mapping("/start")
    public Map<String, Object> startStrategy(@Header("Authorization") String authorization,
                                             @Body StartStrategyRequest request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 验证 token 并获取用户名
            String username = getUsernameFromToken(authorization);
            if (username == null) {
                result.put("code", 401);
                result.put("message", "未授权，请先登录");
                result.put("success", false);
                return result;
            }

            // 获取用户ID
            int userId = getUserIdFromUsername(username);
            if (userId <= 0) {
                result.put("code", 401);
                result.put("message", "用户不存在");
                result.put("success", false);
                return result;
            }

            // 初始化账号（TradeSession 和资产余额）
            initializeAccounts(username);

            // 解析参数（使用默认值或从请求中获取）
            StrategyService.StrategyConfig config = new StrategyService.StrategyConfig();
            
            // 账户配置
            config.buyAccountIds = request.buyAccountIds != null 
                ? convertIntArrayToShortArray(request.buyAccountIds) 
                : new short[]{1, 2, 3, 4, 5};
            config.sellAccountIds = request.sellAccountIds != null 
                ? convertIntArrayToShortArray(request.sellAccountIds) 
                : new short[]{6, 7, 8, 9, 10};
            
            // 价格配置（前端传普通数值，接口层自动转换为放大后的值）
            config.minPriceE8 = (long) ((request.minPrice != null ? request.minPrice : 30.0) * ScaleConstants.SCALE_E8);
            config.maxPriceE8 = (long) ((request.maxPrice != null ? request.maxPrice : 45.0) * ScaleConstants.SCALE_E8);
            config.tickSizeE8 = (long) ((request.tickSize != null ? request.tickSize : 0.01) * ScaleConstants.SCALE_E8);
            
            // 策略参数
            config.volatilityPercent = request.volatilityPercent != null ? request.volatilityPercent : 3.0;
            config.baseAssetId = request.baseAssetId != null ? request.baseAssetId : "H2";
            config.quoteAssetId = "USDT"; // 固定为 USDT，不从请求中获取
            config.exchangeId = request.exchangeId != null ? request.exchangeId : (short) Exchange.DYDX.id();
            
            // 时间配置（前端传秒，接口层自动转换为毫秒）
            config.cycleDurationMs = (request.cycleDuration != null ? request.cycleDuration : 3L * 3600L) * 1000L; // 默认3小时
            // 目标量（前端传普通数值，接口层自动转换为放大后的值）
            config.targetVolumeE8 = (long) ((request.targetVolume != null ? request.targetVolume : 500.0) * ScaleConstants.SCALE_E8);
            config.triggerIntervalMs = (request.triggerInterval != null ? request.triggerInterval : 3L) * 1000L; // 默认3秒
            
            // 其他配置
            config.enableVolumeTarget = request.enableVolumeTarget != null ? request.enableVolumeTarget : true;
            config.makerCounts = request.makerCounts != null ? request.makerCounts : 6;
            config.noiseFactory = request.noiseFactory != null ? request.noiseFactory : 0.5;
            
            // 事件调度器配置（可选，前端传秒，接口层自动转换为毫秒）
            if (request.minInterval != null) {
                config.minIntervalMs = request.minInterval * 1000L;
            }
            if (request.maxInterval != null) {
                config.maxIntervalMs = request.maxInterval * 1000L;
            }
            
            // 策略周期配置（可选）
            if (request.progressThreshold != null) {
                if (request.progressThreshold <= 0.0 || request.progressThreshold >= 1.0) {
                    result.put("code", 400);
                    result.put("message", "progressThreshold 必须在 0.0~1.0 之间");
                    result.put("success", false);
                    return result;
                }
                config.progressThreshold = request.progressThreshold;
            }
            
            // 回调配置参数（可选，使用默认值）
            config.minCorrectionIntervalRatio = request.minCorrectionIntervalRatio;
            config.maxCorrectionIntervalRatio = request.maxCorrectionIntervalRatio;
            config.minCorrectionAmplitudePercent = request.minCorrectionAmplitudePercent;
            config.maxCorrectionAmplitudePercent = request.maxCorrectionAmplitudePercent;
            config.minCorrectionDurationRatio = request.minCorrectionDurationRatio;
            config.maxCorrectionDurationRatio = request.maxCorrectionDurationRatio;
            config.convergenceThresholdPercent = request.convergenceThresholdPercent;
            
            // 回调参数配置（可选，使用默认值或从请求中获取）
            config.minCorrectionIntervalRatio = request.minCorrectionIntervalRatio != null ? request.minCorrectionIntervalRatio : 0.015;
            config.maxCorrectionIntervalRatio = request.maxCorrectionIntervalRatio != null ? request.maxCorrectionIntervalRatio : 0.08;
            config.minCorrectionAmplitudePercent = request.minCorrectionAmplitudePercent != null ? request.minCorrectionAmplitudePercent : 2.0;
            config.maxCorrectionAmplitudePercent = request.maxCorrectionAmplitudePercent != null ? request.maxCorrectionAmplitudePercent : 6.0;
            config.minCorrectionDurationRatio = request.minCorrectionDurationRatio != null ? request.minCorrectionDurationRatio : 0.017;
            config.maxCorrectionDurationRatio = request.maxCorrectionDurationRatio != null ? request.maxCorrectionDurationRatio : 0.04;
            config.convergenceThresholdPercent = request.convergenceThresholdPercent != null ? request.convergenceThresholdPercent : 5.0;

            // 构建交易对符号（用于订阅订单簿）
            String symbol = config.baseAssetId + "-" + config.quoteAssetId;
            config.symbolId = SymbolRegistry.getInstance().get(symbol.replace("-",""));

            
            // 在启动策略之前，先订阅订单簿和账户订单
            subscribeOrderBook(symbol);
            subscribeAccountOrders(config.buyAccountIds, config.sellAccountIds);

            // 启动策略（按 symbolId 路由）
            String message = getStrategyService().startStrategy(config, userId);
            
            result.put("code", 200);
            result.put("message", message);
            result.put("success", message.contains("成功"));
            result.put("symbolId", config.symbolId);
        } catch (Exception e) {
            LOG.error("启动策略失败", e);
            result.put("code", 500);
            result.put("message", "启动策略失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    @Post
    @Mapping("/config-account")
    public Map<String, Object> startStrategy(@Body List<Map<String,Object>> params) {
        Map<String, Object> result = new HashMap<>();
        return result;
    }

    /**
     * 停止指定 symbolId 的策略。
     * POST /api/strategy/stop
     * 
     * 请求体：{"symbolId": 5} 或 {"symbolId": 5, "stopAll": false}
     * 如果 stopAll=true，则停止所有策略
     */
    @Post
    @Mapping("/stop")
    public Map<String, Object> stopStrategy(@Body Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            boolean stopAll = parseBoolean(params.get("stopAll"), false);
            String message;
            
            if (stopAll) {
                // 停止所有策略
                message = getStrategyService().stopAllStrategies();
                // 退订所有策略的订阅
                unsubscribeAllStrategies();
            } else {
                // 停止指定 symbolId 的策略
                short symbolId = parseShort(params.get("symbolId"), (short) 0);
                if (symbolId == 0) {
                    result.put("code", 400);
                    result.put("message", "请指定 symbolId 或设置 stopAll=true");
                    result.put("success", false);
                    return result;
                }
                // 先获取策略信息（用于退订），再停止策略
                StrategyService.StrategyInfo strategyInfo = getStrategyService().getStrategyInfo(symbolId);
                message = getStrategyService().stopStrategy(symbolId);
                // 退订该策略的订阅
                if (strategyInfo != null) {
                    unsubscribeStrategy(strategyInfo);
                }
            }
            
            result.put("code", 200);
            result.put("message", message);
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("停止策略失败", e);
            result.put("code", 500);
            result.put("message", "停止策略失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 获取策略状态。
     * GET /api/strategy/status?symbolId=5
     * 如果不指定 symbolId，返回所有策略的状态
     */
    @Get
    @Mapping("/status")
    public Map<String, Object> getStatus(@org.noear.solon.annotation.Param("symbolId") Short symbolId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (symbolId != null && symbolId > 0) {
                // 获取指定 symbolId 的策略状态
                StrategyService.StrategyStatus status = getStrategyService().getStatus(symbolId);
                result.put("code", 200);
                result.put("data", status);
                result.put("success", true);
            } else {
                // 获取所有策略的状态
                java.util.Map<Short, StrategyService.StrategyStatus> allStatus = getStrategyService().getAllStatus();
                result.put("code", 200);
                result.put("data", allStatus);
                result.put("success", true);
            }
        } catch (Exception e) {
            LOG.error("获取策略状态失败", e);
            result.put("code", 500);
            result.put("message", "获取策略状态失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 获取当前交易员正在运行的所有策略。
     * GET /api/strategy/my-strategies
     * 
     * 请求头：Authorization: Bearer {token}
     */
    @Get
    @Mapping("/strategies")
    public Map<String, Object> getStrategies(@Header("Authorization") String authorization) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 验证 token 并获取用户名
            String username = getUsernameFromToken(authorization);
            if (username == null) {
                result.put("code", 401);
                result.put("message", "未授权，请先登录");
                result.put("success", false);
                return result;
            }

            // 获取用户ID
            int userId = getUserIdFromUsername(username);
            if (userId <= 0) {
                result.put("code", 401);
                result.put("message", "用户不存在");
                result.put("success", false);
                return result;
            }

            // 获取该交易员的所有策略
            java.util.List<StrategyService.StrategyStatus> strategies = getStrategyService().getStrategiesByUserId(userId);
            
            result.put("code", 200);
            result.put("data", strategies);
            result.put("success", true);
            result.put("count", strategies.size());
        } catch (Exception e) {
            LOG.error("获取交易员策略列表失败", e);
            result.put("code", 500);
            result.put("message", "获取策略列表失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    // ========== 辅助方法 ==========

    /**
     * 将 int 数组转换为 short 数组。
     */
    private short[] convertIntArrayToShortArray(int[] intArray) {
        if (intArray == null) {
            return new short[0];
        }
        short[] result = new short[intArray.length];
        for (int i = 0; i < intArray.length; i++) {
            result[i] = (short) intArray[i];
        }
        return result;
    }

    private long parseLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private short parseShort(Object value, short defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).shortValue();
        }
        return Short.parseShort(value.toString());
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private String parseString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }
    
    /**
     * 解析价格参数（前端传普通数值，自动转换为放大后的值）。
     * 例如：前端传 30.0，返回 30.0 * ScaleConstants.SCALE_E8
     * 
     * @param value 前端传入的普通数值（如 30.0）
     * @param defaultValue 默认值（普通数值，如 30.0）
     * @return 放大后的值（如 30.0 * ScaleConstants.SCALE_E8）
     */
    private long parsePriceToE8(Object value, double defaultValue) {
        double price;
        if (value == null) {
            price = defaultValue;
        } else if (value instanceof Number) {
            price = ((Number) value).doubleValue();
        } else {
            price = Double.parseDouble(value.toString());
        }
        return (long) (price * ScaleConstants.SCALE_E8);
    }
    
    /**
     * 解析数量参数（前端传普通数值，自动转换为放大后的值）。
     * 例如：前端传 500.0，返回 500.0 * ScaleConstants.SCALE_E8
     * 
     * @param value 前端传入的普通数值（如 500.0）
     * @param defaultValue 默认值（普通数值，如 500.0）
     * @return 放大后的值（如 500.0 * ScaleConstants.SCALE_E8）
     */
    private long parseVolumeToE8(Object value, double defaultValue) {
        double volume;
        if (value == null) {
            volume = defaultValue;
        } else if (value instanceof Number) {
            volume = ((Number) value).doubleValue();
        } else {
            volume = Double.parseDouble(value.toString());
        }
        return (long) (volume * ScaleConstants.SCALE_E8);
    }
    
    /**
     * 解析时间参数（前端传秒，自动转换为毫秒）。
     * 例如：前端传 3，返回 3 * 1000
     * 
     * @param value 前端传入的秒数（如 3）
     * @param defaultValue 默认值（秒数，如 3）
     * @return 毫秒数（如 3 * 1000）
     */
    private long parseSecondsToMs(Object value, long defaultValue) {
        long seconds;
        if (value == null) {
            seconds = defaultValue;
        } else if (value instanceof Number) {
            seconds = ((Number) value).longValue();
        } else {
            seconds = Long.parseLong(value.toString());
        }
        return seconds * 1000L;
    }

    /**
     * 初始化账号（TradeSession 和资产余额）。
     * 从 AccountConfigService 获取交易员的账号并初始化。
     *
     * @param username 交易员用户名
     */
    private void initializeAccounts(String username) {
        try {
            NettySidecarGateway dydxGateway = appContext.getDydxGateway();
            PositionManager positionManager = appContext.getPositionManager();
            
            if (dydxGateway == null || positionManager == null) {
                LOG.warn("DydxGateway 或 PositionManager 未初始化，无法初始化账号");
                return;
            }

            // 获取交易员的所有账号
            List<AccountInfo> accounts = accountService.getAccounts(username);
            if (accounts.isEmpty()) {
                LOG.warn("交易员 {} 没有配置账号", username);
                return;
            }

            LOG.info("开始初始化交易员 {} 的 {} 个账号...", username, accounts.size());

            // 为每个账号初始化 TradeSession 和资产余额
            for (AccountInfo account : accounts) {
                try {
                    // 1. 初始化 TradeSession（用于下单）
                    dydxGateway.initializeSession(
                            account.accountId,
                            account.accountName,
                            account.mnemonicPhrase
                    );

                    // 2. 初始化账户资产余额（阻塞操作，从 dYdX REST API 同步）
                    LOG.info("正在初始化账户资产: accountId={}, address={}...",
                            account.accountId, account.address);
                    positionManager.initializeFromDydx(
                            account.accountId,
                            account.address,
                            account.subaccountNumber
                    );

                    LOG.info("账户初始化成功: accountId={}, accountName={}, address={}, subaccountNumber={}",
                            account.accountId, account.accountName, account.address, account.subaccountNumber);
                } catch (Exception e) {
                    LOG.error("账户初始化失败: accountId={}, address={}",
                            account.accountId, account.address, e);
                    // 继续处理下一个账号，不中断整个初始化流程
                }
            }

            LOG.info("交易员 {} 的账号初始化完成", username);
        } catch (Exception e) {
            LOG.error("初始化账号时发生错误", e);
            throw new RuntimeException("初始化账号失败: " + e.getMessage(), e);
        }
    }

    /**
     * 订阅订单簿（v4_orderbook 频道）。
     *
     * @param symbol 交易对符号（如 "H2-USDT"）
     */
    private void subscribeOrderBook(String symbol) {
        try {
            DydxMarketDataConnector dydxConnector = appContext.getDydxConnector();
            if (dydxConnector == null) {
                LOG.warn("DydxMarketDataConnector 未初始化，无法订阅订单簿");
                return;
            }

            dydxConnector.subscribeOrderBook(symbol);
            LOG.info("已订阅订单簿: symbol={}", symbol);
        } catch (Exception e) {
            LOG.error("订阅订单簿时发生错误: symbol={}", symbol, e);
            throw new RuntimeException("订阅订单簿失败: " + e.getMessage(), e);
        }
    }

    /**
     * 订阅指定账户的订单更新。
     * 根据 buyAccountIds 和 sellAccountIds 订阅对应的账户。
     *
     * @param buyAccountIds 买单账户 ID 数组
     * @param sellAccountIds 卖单账户 ID 数组
     */
    private void subscribeAccountOrders(short[] buyAccountIds, short[] sellAccountIds) {
        try {
            DydxMarketDataConnector dydxConnector = appContext.getDydxConnector();
            if (dydxConnector == null) {
                LOG.warn("DydxMarketDataConnector 未初始化，无法订阅账户订单");
                return;
            }

            // 获取当前用户（从 token 中获取，这里简化处理，实际应该从上下文获取）
            // 注意：这里需要从请求上下文中获取用户名，暂时使用所有交易员的账号
            // 实际应该只获取当前交易员的账号
            
            // 合并所有需要订阅的账户 ID
            java.util.Set<Short> accountIdSet = new java.util.HashSet<>();
            for (short id : buyAccountIds) {
                accountIdSet.add(id);
            }
            for (short id : sellAccountIds) {
                accountIdSet.add(id);
            }

            // 遍历所有交易员的账号，找到匹配的账号并订阅
            int subscribedCount = 0;
            try {
                for (String username : accountService.getAllTraders()) {
                    List<AccountInfo> accounts = accountService.getAccounts(username);
                    for (AccountInfo account : accounts) {
                        if (accountIdSet.contains((short) account.accountId)) {
                            try {
                                dydxConnector.configureAccountOrders(account.address, account.subaccountNumber);
                                LOG.info("已订阅账户订单: accountId={}, address={}, subaccountNumber={}",
                                        account.accountId, account.address, account.subaccountNumber);
                                subscribedCount++;
                            } catch (Exception e) {
                                LOG.error("订阅账户订单失败: accountId={}, address={}",
                                        account.accountId, account.address, e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("获取交易员列表失败", e);
            }

            LOG.info("账户订单订阅完成: 共订阅 {} 个账户", subscribedCount);
        } catch (Exception e) {
            LOG.error("订阅账户订单时发生错误", e);
            throw new RuntimeException("订阅账户订单失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 Authorization header 中提取 token 并获取用户名。
     */
    private String getUsernameFromToken(String authorization) {
        if (authorization == null || authorization.isEmpty()) {
            return null;
        }
        
        // 支持 "Bearer {token}" 格式
        String token = authorization;
        if (authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }
        
        return tokenManager.getUsername(token);
    }

    /**
     * 根据用户名获取用户ID。
     */
    private int getUserIdFromUsername(String username) {
        try {
            UserService.UserInfo user = userService.getUserByUsername(username);
            return user != null ? user.id : 0;
        } catch (Exception e) {
            LOG.error("获取用户ID失败: username={}", username, e);
            return 0;
        }
    }

    /**
     * 退订策略的订阅信息（订单簿和账户订单）。
     *
     * @param strategyInfo 策略信息
     */
    private void unsubscribeStrategy(StrategyService.StrategyInfo strategyInfo) {
        try {
            DydxMarketDataConnector dydxConnector = appContext.getDydxConnector();
            if (dydxConnector == null) {
                LOG.warn("DydxMarketDataConnector 未初始化，无法退订");
                return;
            }

            // 1. 退订订单簿
            dydxConnector.unsubscribeOrderBook(strategyInfo.symbol);
            LOG.info("已退订订单簿: symbol={}", strategyInfo.symbol);

            // 2. 退订账户订单（合并 buyAccountIds 和 sellAccountIds）
            java.util.Set<Short> accountIdSet = new java.util.HashSet<>();
            for (short id : strategyInfo.buyAccountIds) {
                accountIdSet.add(id);
            }
            for (short id : strategyInfo.sellAccountIds) {
                accountIdSet.add(id);
            }

            // 遍历所有交易员的账号，找到匹配的账号并退订
            int unsubscribedCount = 0;
            try {
                for (String username : accountService.getAllTraders()) {
                    List<AccountInfo> accounts = accountService.getAccounts(username);
                    for (AccountInfo account : accounts) {
                        if (accountIdSet.contains((short) account.accountId)) {
                            try {
                                dydxConnector.unsubscribeAccountOrders(account.address, account.subaccountNumber);
                                LOG.info("已退订账户订单: accountId={}, address={}, subaccountNumber={}",
                                        account.accountId, account.address, account.subaccountNumber);
                                unsubscribedCount++;
                            } catch (Exception e) {
                                LOG.error("退订账户订单失败: accountId={}, address={}",
                                        account.accountId, account.address, e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("获取交易员列表失败", e);
            }

            LOG.info("策略退订完成: symbol={}, 共退订 {} 个账户", strategyInfo.symbol, unsubscribedCount);
        } catch (Exception e) {
            LOG.error("退订策略订阅时发生错误", e);
        }
    }

    /**
     * 退订所有策略的订阅信息。
     */
    private void unsubscribeAllStrategies() {
        try {
            java.util.Map<Short, StrategyService.StrategyStatus> allStatus = getStrategyService().getAllStatus();
            for (Short symbolId : allStatus.keySet()) {
                StrategyService.StrategyInfo strategyInfo = getStrategyService().getStrategyInfo(symbolId);
                if (strategyInfo != null) {
                    unsubscribeStrategy(strategyInfo);
                }
            }
        } catch (Exception e) {
            LOG.error("退订所有策略订阅时发生错误", e);
        }
    }
}

