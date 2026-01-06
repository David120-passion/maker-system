package com.xinyue.maker.common;

/**
 * 资产转移命令。
 * 用于在账户之间转移资产。
 */
public final class TransferCommand {
    public short fromAccountId;  // 转出账户 ID
    public short toAccountId;    // 转入账户 ID
    public short symbolId;       // 资产符号 ID（assetId）
    public long qtyE8;            // 转移数量（放大 1e8）
    public short exchangeId;      // 交易所 ID
}


