package com.xinyue.maker.core.balance;

import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.TransferCommand;
import com.xinyue.maker.core.gateway.ExecutionGateway;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.io.output.ExecutionGatewayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 账户资产均衡器。
 * <p>
 * 功能：
 * 1. 监听买组账户的 USDT 余额，如果低于阈值，从卖组账户转移 USDT 到买组账户，保持每个买组账户的 USDT 都充足
 * 2. 监听卖组账户的现货余额（如 BTC/ETH），如果低于阈值，从买组账户转移代币到卖组账户
 * <p>
 * 均衡策略：
 * - 对于买组账户：如果某个账户 USDT 不足，从卖组账户中余额最多的账户转移
 * - 对于卖组账户：如果某个账户现货不足，从买组账户中余额最多的账户转移
 * <p>
 * 在项目启动时启动，定期检查并执行资产转移。
 */
public final class AccountBalanceBalancer {

    private static final Logger LOG = LoggerFactory.getLogger(AccountBalanceBalancer.class);

    // 配置参数
    private final short[] buyAccountIds;      // 买入账户组
    private final short[] sellAccountIds;     // 卖出账户组
    private final short quoteAssetId;         // 报价资产 ID（如 USDT）
    private final short baseAssetId;          // 基础资产 ID（如 BTC/ETH）
    private final short exchangeId;          // 交易所 ID
    private final long minQuoteBalanceE8;     // 买组账户最小 USDT 余额阈值（放大 1e8）
    private final long minBaseBalanceE8;      // 卖组账户最小现货余额阈值（放大 1e8）
    private final long transferAmountE8;      // 每次转移的数量（放大 1e8）

    // 依赖组件
    private final PositionManager positionManager;
    private final ExecutionGatewayManager gatewayManager;
    private final AssetRegistry assetRegistry;

    // 定时任务
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    // 防止频繁转移的冷却时间（毫秒）
    private static final long COOLDOWN_MS = 30_000; // 30 秒
    private volatile long lastQuoteTransferTime = 0;
    private volatile long lastBaseTransferTime = 0;

    public AccountBalanceBalancer(
            short[] buyAccountIds,
            short[] sellAccountIds,
            short quoteAssetId,
            short baseAssetId,
            short exchangeId,
            long minQuoteBalanceE8,
            long minBaseBalanceE8,
            long transferAmountE8,
            PositionManager positionManager,
            ExecutionGatewayManager gatewayManager) {

        if (buyAccountIds == null || buyAccountIds.length == 0) {
            throw new IllegalArgumentException("buyAccountIds 不能为空");
        }
        if (sellAccountIds == null || sellAccountIds.length == 0) {
            throw new IllegalArgumentException("sellAccountIds 不能为空");
        }
        if (minQuoteBalanceE8 <= 0) {
            throw new IllegalArgumentException("minQuoteBalanceE8 必须大于 0");
        }
        if (minBaseBalanceE8 <= 0) {
            throw new IllegalArgumentException("minBaseBalanceE8 必须大于 0");
        }
        if (transferAmountE8 <= 0) {
            throw new IllegalArgumentException("transferAmountE8 必须大于 0");
        }

        this.buyAccountIds = buyAccountIds.clone();
        this.sellAccountIds = sellAccountIds.clone();
        this.quoteAssetId = quoteAssetId;
        this.baseAssetId = baseAssetId;
        this.exchangeId = exchangeId;
        this.minQuoteBalanceE8 = minQuoteBalanceE8;
        this.minBaseBalanceE8 = minBaseBalanceE8;
        this.transferAmountE8 = transferAmountE8;
        this.positionManager = positionManager;
        this.gatewayManager = gatewayManager;
        this.assetRegistry = AssetRegistry.getInstance();

        // 创建单线程定时任务执行器
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AccountBalanceBalancer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动均衡器。
     * 每 10 秒检查一次账户余额，并在需要时执行转移。
     */
    public void start() {
        if (running) {
            LOG.warn("AccountBalanceBalancer 已经在运行中");
            return;
        }

        running = true;
        LOG.info("启动账户资产均衡器: buyAccounts={}, sellAccounts={}, quoteAsset={}, baseAsset={}, " +
                "minQuoteBalance={}, minBaseBalance={}, transferAmount={}",
                java.util.Arrays.toString(buyAccountIds),
                java.util.Arrays.toString(sellAccountIds),
                assetRegistry.getAsset(quoteAssetId),
                assetRegistry.getAsset(baseAssetId),
                minQuoteBalanceE8 / 1_0000_0000.0,
                minBaseBalanceE8 / 1_0000_0000.0,
                transferAmountE8 / 1_0000_0000.0);

        // 每 10 秒执行一次检查
        scheduler.scheduleWithFixedDelay(
                this::checkAndBalance,
                10,  // 初始延迟 10 秒
                10,  // 每 10 秒执行一次
                TimeUnit.SECONDS
        );
    }

    /**
     * 停止均衡器。
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("账户资产均衡器已停止");
    }

    /**
     * 检查并执行资产均衡。
     */
    private void checkAndBalance() {
        if (!running) {
            return;
        }

        try {
            // 1. 检查买组账户的 USDT 余额
            checkAndBalanceQuoteAsset();

            // 2. 检查卖组账户的现货余额
            checkAndBalanceBaseAsset();
        } catch (Exception e) {
            LOG.error("资产均衡检查失败", e);
        }
    }

    /**
     * 检查买组账户的 USDT 余额，如果不足，从卖组账户转移。
     * 均衡策略：找到余额最少的买组账户，从余额最多的卖组账户转移。
     */
    private void checkAndBalanceQuoteAsset() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastQuoteTransferTime < COOLDOWN_MS) {
            return; // 冷却时间未到
        }

        String quoteSymbol = assetRegistry.getAsset(quoteAssetId);

        // 1. 查询所有买组账户的 USDT 余额
        short minBalanceAccountId = 0;
        long minBalance = Long.MAX_VALUE;

        for (short buyAccountId : buyAccountIds) {
            long balance = positionManager.getFreeBalance(buyAccountId, quoteAssetId);

            if (balance < minBalance) {
                minBalance = balance;
                minBalanceAccountId = buyAccountId;
            }

            LOG.debug("买组账户 {} USDT 余额: {}", buyAccountId, balance / 1_0000_0000.0);
        }

        // 2. 如果余额最少的账户低于阈值，需要转移
        if (minBalanceAccountId > 0 && minBalance < minQuoteBalanceE8) {
            long neededAmount = minQuoteBalanceE8 - minBalance;
            long transferQty = Math.min(neededAmount, transferAmountE8);

            // 3. 从卖组账户中找到余额最多的账户
            short maxBalanceAccountId = 0;
            long maxBalance = 0;

            for (short sellAccountId : sellAccountIds) {
                long balance = positionManager.getFreeBalance(sellAccountId, quoteAssetId);

                if (balance > maxBalance && balance >= transferQty) {
                    maxBalance = balance;
                    maxBalanceAccountId = sellAccountId;
                }
            }

            // 4. 执行转移
            if (maxBalanceAccountId > 0) {
                executeTransfer(maxBalanceAccountId, minBalanceAccountId, quoteAssetId, transferQty);
                lastQuoteTransferTime = currentTime;
                LOG.info("从卖组账户 {} 转移 {} {} 到买组账户 {} (当前余额: {}, 转移后余额: {})",
                        maxBalanceAccountId,
                        transferQty / 1_0000_0000.0,
                        quoteSymbol,
                        minBalanceAccountId,
                        minBalance / 1_0000_0000.0,
                        (minBalance + transferQty) / 1_0000_0000.0);
            } else {
                LOG.warn("买组账户 {} {} 余额不足 (当前: {}, 需要: {})，但卖组账户没有足够的余额",
                        minBalanceAccountId,
                        quoteSymbol,
                        minBalance / 1_0000_0000.0,
                        minQuoteBalanceE8 / 1_0000_0000.0);
            }
        }
    }

    /**
     * 检查卖组账户的现货余额，如果不足，从买组账户转移。
     * 均衡策略：找到余额最少的卖组账户，从余额最多的买组账户转移。
     */
    private void checkAndBalanceBaseAsset() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBaseTransferTime < COOLDOWN_MS) {
            return; // 冷却时间未到
        }

        String baseSymbol = assetRegistry.getAsset(baseAssetId);

        // 1. 查询所有卖组账户的现货余额
        short minBalanceAccountId = 0;
        long minBalance = Long.MAX_VALUE;

        for (short sellAccountId : sellAccountIds) {
            long balance = positionManager.getFreeBalance(sellAccountId, baseAssetId);

            if (balance < minBalance) {
                minBalance = balance;
                minBalanceAccountId = sellAccountId;
            }

            LOG.debug("卖组账户 {} {} 余额: {}", sellAccountId, baseSymbol, balance / 1_0000_0000.0);
        }

        // 2. 如果余额最少的账户低于阈值，需要转移
        if (minBalanceAccountId > 0 && minBalance < minBaseBalanceE8) {
            long neededAmount = minBaseBalanceE8 - minBalance;
            long transferQty = Math.min(neededAmount, transferAmountE8);

            // 3. 从买组账户中找到余额最多的账户
            short maxBalanceAccountId = 0;
            long maxBalance = 0;

            for (short buyAccountId : buyAccountIds) {
                long balance = positionManager.getFreeBalance(buyAccountId, baseAssetId);

                if (balance > maxBalance && balance >= transferQty) {
                    maxBalance = balance;
                    maxBalanceAccountId = buyAccountId;
                }
            }

            // 4. 执行转移
            if (maxBalanceAccountId > 0) {
                executeTransfer(maxBalanceAccountId, minBalanceAccountId, baseAssetId, transferQty);
                lastBaseTransferTime = currentTime;
                LOG.info("从买组账户 {} 转移 {} {} 到卖组账户 {} (当前余额: {}, 转移后余额: {})",
                        maxBalanceAccountId,
                        transferQty / 1_0000_0000.0,
                        baseSymbol,
                        minBalanceAccountId,
                        minBalance / 1_0000_0000.0,
                        (minBalance + transferQty) / 1_0000_0000.0);
            } else {
                LOG.warn("卖组账户 {} {} 余额不足 (当前: {}, 需要: {})，但买组账户没有足够的余额",
                        minBalanceAccountId,
                        baseSymbol,
                        minBalance / 1_0000_0000.0,
                        minBaseBalanceE8 / 1_0000_0000.0);
            }
        }
    }

    /**
     * 执行资产转移。
     *
     * @param fromAccountId 转出账户ID
     * @param toAccountId 转入账户ID
     * @param assetId 资产ID
     * @param qtyE8 转移数量（放大 1e8）
     */
    private void executeTransfer(short fromAccountId, short toAccountId, short assetId, long qtyE8) {
        Exchange exchange = Exchange.fromId(exchangeId);
        ExecutionGateway gateway = gatewayManager.getGateway(exchange);
        if (gateway == null) {
            LOG.error("未找到交易所 {} 的 ExecutionGateway，无法执行转移", exchange);
            return;
        }

        // 创建转移指令
        TransferCommand cmd = new TransferCommand();
        cmd.fromAccountId = fromAccountId;
        cmd.toAccountId = toAccountId;
        cmd.symbolId = assetId;
        cmd.qtyE8 = qtyE8;
        cmd.exchangeId = exchangeId;

        // 发送转移指令（非阻塞）
        gateway.transfer(cmd);
    }
}

