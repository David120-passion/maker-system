package com.xinyue.maker.core.position;

import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.infra.MetricsService;
import com.xinyue.maker.io.rest.DydxRestClient;
import com.xinyue.maker.strategy.InternalRangeOscillatorStrategy2;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.IntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 每个账户维护一份基础资产 / 报价资产的双资产账本。
 */
public final class PositionManager {

    private static final Logger LOG = LoggerFactory.getLogger(InternalRangeOscillatorStrategy2.class);

    private final MetricsService metricsService;
    private final DydxRestClient dydxRestClient;
    private final AssetRegistry assetRegistry;

    // 一级索引：AccountID -> Portfolio
    private final Int2ObjectHashMap<AccountPortfolio> accounts = new Int2ObjectHashMap<>();
    
    // 随机数生成器（用于随机起点轮询选择账户）
    private final java.util.Random random = new java.util.Random();

    public PositionManager(MetricsService metricsService) {
        this.metricsService = metricsService;
        this.dydxRestClient = new DydxRestClient();
        this.assetRegistry = AssetRegistry.getInstance();
    }


    // === 核心操作：预扣资金 ===
    // 返回 true 表示成功，false 表示余额不足
    // 注意：这里的 symbolId 实际上是 assetId（资产ID，如 USDT、BTC），不是交易对符号ID
    public boolean reserve(int accountId, int assetId, long amount) {
        // 1. 路由：找人 (O(1))
        AccountPortfolio portfolio = accounts.get(accountId);
        if (portfolio == null) {
            return false; // 账号不存在
        }

        // 2. 路由：找币 (O(1))，symbolId 实际为 assetId
        Asset asset = portfolio.getAsset(assetId);

        // 3. 检查与执行
        if (asset.free >= amount) {
            asset.free -= amount;
            asset.locked += amount;
            return true;
        } else {
            return false; // 余额不足
        }
    }

    // === 初始化方法 ===
    public void registerAccount(int accountId) {
        accounts.put(accountId, new AccountPortfolio(accountId));
    }

    /**
     * 从 dYdX REST API 初始化账户资产。
     * 在 PositionManager 初始化时调用此方法来同步账户余额。
     *
     * @param accountId 内部账户 ID
     * @param address dYdX 账户地址（如 "h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt"）
     * @param subaccountNumber 子账户编号（通常为 0）
     * @throws IOException 网络或解析错误
     * @throws InterruptedException 中断异常
     */
    public void initializeFromDydx(int accountId, String address, int subaccountNumber) 
            throws IOException, InterruptedException {
        // 1. 调用 dYdX API 获取账户信息
        String json = dydxRestClient.getSubaccount(address, subaccountNumber);
        
        // 2. 解析资产信息
        DydxRestClient.AssetInfo[] assetInfos = dydxRestClient.parseAssetPositions(json);
        
        // 3. 注册账户（如果不存在）
        AccountPortfolio portfolio = accounts.computeIfAbsent(accountId, id -> new AccountPortfolio(id));
        
        // 4. 初始化每个资产
        for (DydxRestClient.AssetInfo assetInfo : assetInfos) {
            // 将资产符号（如 "USDT", "ORCL"）转换为 assetId
            String assetSymbol = assetInfo.symbol;
            short assetId = assetRegistry.get(assetSymbol);
            
            // 将 size 字符串转换为 long（放大 1e8）
            long sizeE8 = DydxRestClient.parseSizeToLong(assetInfo.size);
            
            // 设置资产余额（初始时全部为 free，没有 locked）
            // 注意：AccountPortfolio.getAsset() 使用 assetId 作为 key
            Asset asset = portfolio.getAsset(assetId);
            asset.free = sizeE8;
            asset.locked = 0;
        }
    }

    public void onExecution(CoreEvent event) {
        metricsService.recordPositionUpdate(event.accountId, event.quantity);
    }

    /**
     * 根据指定的 assetId 和余额阈值，获取一批满足条件的账户。
     * <p>
     * 检查账户的可用余额（free）是否大于等于指定阈值。
     *
     * @param assetId 资产 ID（assetId，如 USDT、BTC），不是交易对符号ID
     * @param minBalance 最小余额阈值（放大 1e8）
     * @return 满足条件的账户ID列表（使用 Agrona IntArrayList，零GC）
     */
    public IntArrayList getAccountsByBalance(int assetId, long minBalance) {
        IntArrayList result = new IntArrayList();
        
        // 遍历所有账户
        accounts.forEach((accountId, portfolio) -> {
            Asset asset = portfolio.getAsset(assetId);
            // 检查可用余额是否满足阈值
            if (asset.free >= minBalance) {
                result.add(accountId);
            }
        });
        
        return result;
    }

    /**
     * 根据指定的 assetId 和余额阈值，获取一批满足条件的账户（检查总余额）。
     * <p>
     * 检查账户的总余额（free + locked）是否大于等于指定阈值。
     *
     * @param assetId 资产 ID（assetId，如 USDT、BTC），不是交易对符号ID
     * @param minTotalBalance 最小总余额阈值（放大 1e8）
     * @return 满足条件的账户ID列表（使用 Agrona IntArrayList，零GC）
     */
    public IntArrayList getAccountsByTotalBalance(int assetId, long minTotalBalance) {
        IntArrayList result = new IntArrayList();
        
        // 遍历所有账户
        accounts.forEach((accountId, portfolio) -> {
            Asset asset = portfolio.getAsset(assetId);
            // 检查总余额是否满足阈值
            if (asset.total() >= minTotalBalance) {
                result.add(accountId);
            }
        });
        
        return result;
    }
    
    /**
     * 释放余额（撤单确认后调用）。
     * 将锁定余额转回可用余额。
     *
     * @param accountId 账户ID
     * @param assetId 资产ID（assetId，如 USDT、BTC），不是交易对符号ID
     * @param amountE8 释放数量（放大 1e8）
     */
    public void release(int accountId, int assetId, long amountE8) {
        AccountPortfolio portfolio = accounts.get(accountId);
        if (portfolio == null) {
            return; // 账户不存在
        }
        
        Asset asset = portfolio.getAsset(assetId);
        
        // 确保不会释放超过锁定余额的数量
        long releaseAmount = Math.min(amountE8, asset.locked);
        
        // 释放余额：从 locked 转回 free
        asset.locked -= releaseAmount;
        asset.free += releaseAmount;
    }
    
    /**
     * 获取账户的可用余额。
     *
     * @param accountId 账户 ID
     * @param assetId 资产 ID（assetId，如 USDT、BTC），不是交易对符号ID
     * @return 可用余额（放大 1e8）
     */
    public long getFreeBalance(int accountId, int assetId) {
        AccountPortfolio portfolio = accounts.get(accountId);
        if (portfolio == null) {
            return 0L;
        }
        Asset asset = portfolio.getAsset(assetId);
        return asset.free;
    }
    
    /**
     * 获取账户的锁定余额。
     *
     * @param accountId 账户 ID
     * @param assetId 资产 ID（assetId，如 USDT、BTC），不是交易对符号ID
     * @return 锁定余额（放大 1e8）
     */
    public long getLockedBalance(int accountId, int assetId) {
        AccountPortfolio portfolio = accounts.get(accountId);
        if (portfolio == null) {
            return 0L;
        }
        Asset asset = portfolio.getAsset(assetId);
        return asset.locked;
    }
    
    /**
     * 从账户列表中选择一个有足够余额的账户（随机起点轮询模式）。
     * <p>
     * 算法说明：
     * - 每次调用时随机生成一个起始索引（0 到 length-1）
     * - 从起始索引开始遍历数组，如果到了末尾就绕回开头（取模运算）
     * - 直到检查完所有账户
     * - 效果：确保只要有账户余额充足，每个账户被选中的概率是均等的，消除"热点账户"问题
     * 
     * @param accountIds 账户 ID 列表
     * @param assetId 资产 ID（assetId，如 USDT、BTC），不是交易对符号ID
     * @param requiredBalanceE8 所需余额（放大 10^8 倍）
     * @return 选中的账户 ID，如果没有则返回 -1
     */
    public short selectAccountWithBalance(short[] accountIds, short assetId, long requiredBalanceE8) {
        if (accountIds.length == 0) {
            return -1;
        }
        
        // 随机生成起始索引（0 到 length-1）
        int startIndex = random.nextInt(accountIds.length);
        // 从 startIndex 开始遍历，使用取模运算实现循环（到末尾后绕回开头）
        for (int i = 0; i < accountIds.length; i++) {
            int index = (startIndex + i) % accountIds.length;
            short accountId = accountIds[index];
//            LOG.info("检查账户{}",accountId);
            long availableBalance = getFreeBalance(accountId, assetId);
            if (availableBalance >= requiredBalanceE8) {
//                LOG.info("选中账户{}",accountId);
                return accountId;
            }
        }
        
        // 所有账户余额都不足
        return -1;
    }

    /**
     * 更新账户余额（从 WebSocket 推送的 assetPositions 数据）。
     * <p>
     * WebSocket 推送的余额是账户的总余额（free + locked）。
     * 我们需要保持 locked 余额不变（由订单系统管理），只更新 free 余额。
     * 
     * @param accountId 账户 ID
     * @param assetIds 资产ID数组
     * @param assetBalances 余额数组（放大 1e8，表示总余额）
     * @param assetCount 资产数量
     */
    public void updateBalances(int accountId, short[] assetIds, long[] assetBalances, int assetCount) {
        // 获取或创建账户组合
        AccountPortfolio portfolio = accounts.computeIfAbsent(accountId, id -> new AccountPortfolio(id));
        
        // 更新每个资产的余额
        for (int i = 0; i < assetCount && i < assetIds.length && i < assetBalances.length; i++) {
            short assetId = assetIds[i];
            if (assetId <= 0) {
                continue; // 跳过无效的资产ID
            }
            
            long newTotalBalanceE8 = assetBalances[i];
            Asset asset = portfolio.getAsset(assetId);
            
            // WebSocket 推送的余额是总余额（free + locked）
            // 保持 locked 余额不变（由订单系统管理），计算新的 free 余额
            long newFreeBalanceE8 = newTotalBalanceE8 - asset.locked;
            
            // 确保 free 余额不为负数（如果 locked 余额超过总余额，说明数据不一致，记录警告）
            if (newFreeBalanceE8 < 0) {
                // 数据不一致：locked 余额超过总余额，将 free 设为 0，并调整 locked
                asset.free = 0;
                asset.locked = newTotalBalanceE8; // 将 locked 调整为总余额
                // TODO: 记录警告日志
            } else {
                asset.free = newFreeBalanceE8;
                // locked 余额保持不变，由订单系统管理
            }
        }
    }

    /**
     * 处理账户转账（从 WebSocket 推送的 transfers 数据）。
     * <p>
     * 转账类型：
     * - TRANSFER_IN（转入）：增加账户余额
     * - TRANSFER_OUT（转出）：减少账户余额
     * 
     * @param accountId 账户 ID
     * @param assetId 资产ID（assetId，如 USDT、BTC）
     * @param amountE8 转账数量（放大 1e8）
     * @param isTransferIn 是否是转入（true=转入，false=转出）
     */
    public void processTransfer(int accountId, short assetId, long amountE8, boolean isTransferIn) {
        if (assetId <= 0 || amountE8 <= 0) {
            return; // 无效的资产ID或数量
        }
        
        // 获取或创建账户组合
        AccountPortfolio portfolio = accounts.computeIfAbsent(accountId, id -> new AccountPortfolio(id));
        Asset asset = portfolio.getAsset(assetId);
        
        if (isTransferIn) {
            // 转入：增加 free 余额
            asset.free += amountE8;
        } else {
            // 转出：优先减少 free 余额，如果 free 不够，再减少 locked 余额
            long reduceFromFree = Math.min(amountE8, asset.free);
            asset.free -= reduceFromFree;
            
            long remaining = amountE8 - reduceFromFree;
            if (remaining > 0) {
                // 如果 free 不够，减少 locked 余额
                asset.locked = Math.max(0, asset.locked - remaining);
            }
        }
    }

    public static void main(String[] args) {

    }
}

