package com.xinyue.maker.web.model;

import com.xinyue.maker.config.AccountConfig;

/**
 * 账号信息模型。
 */
public final class AccountInfo {
    public int accountId;
    public String accountName;
    public String mnemonicPhrase;
    public String address;
    public int subaccountNumber;
    public String username;  // 所属交易员用户名（可能为null，表示未分配）

    // 无参构造函数（SqlUtils 需要）
    public AccountInfo() {
    }

    // 有参构造函数（用于创建对象）
    public AccountInfo(int accountId, String accountName, String mnemonicPhrase,
                      String address, int subaccountNumber) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.mnemonicPhrase = mnemonicPhrase;
        this.address = address;
        this.subaccountNumber = subaccountNumber;
    }

    // 有参构造函数（包含username）
    public AccountInfo(int accountId, String accountName, String mnemonicPhrase,
                      String address, int subaccountNumber, String username) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.mnemonicPhrase = mnemonicPhrase;
        this.address = address;
        this.subaccountNumber = subaccountNumber;
        this.username = username;
    }

    /**
     * 转换为 AccountConfig.AccountInfo（用于兼容现有代码）。
     */
    public AccountConfig.AccountInfo toAccountConfigInfo() {
        return new AccountConfig.AccountInfo(
                accountId,
                accountName,
                mnemonicPhrase,
                address,
                subaccountNumber
        );
    }
}

