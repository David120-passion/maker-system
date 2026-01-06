package com.xinyue.maker.config;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 账户配置读取器。
 * 从 accounts.properties 读取多个账户信息。
 */
public final class AccountConfig {
    
    public static final class AccountInfo {
        public final int accountId;
        public final String accountName;
        public final String mnemonicPhrase;
        public final String address;
        public final int subaccountNumber;
        
        public AccountInfo(int accountId, String accountName, String mnemonicPhrase, 
                          String address, int subaccountNumber) {
            this.accountId = accountId;
            this.accountName = accountName;
            this.mnemonicPhrase = mnemonicPhrase;
            this.address = address;
            this.subaccountNumber = subaccountNumber;
        }
    }
    
    /**
     * 从 accounts.properties 读取所有账户配置。
     * 
     * @return 账户信息列表
     */
    public static List<AccountInfo> loadAccounts() {
        List<AccountInfo> accounts = new ArrayList<>();
        Properties props = new Properties();
        
        try (InputStream is = AccountConfig.class.getClassLoader()
                .getResourceAsStream("accounts.properties")) {
            if (is == null) {
                System.err.println("警告: accounts.properties 文件未找到，使用默认账户配置");
                // 返回空列表或默认账户（根据需求）
                return accounts;
            }
            
            props.load(is);
            
            // 遍历所有账户索引（从 1 开始）
            int index = 1;
            while (true) {
                String idKey = "account." + index + ".id";
                String idValue = props.getProperty(idKey);
                
                if (idValue == null) {
                    // 没有更多账户了
                    break;
                }
                
                int accountId = Integer.parseInt(idValue.trim());
                String accountName = props.getProperty("account." + index + ".name", "");
                String mnemonic = props.getProperty("account." + index + ".mnemonic", "");
                String address = props.getProperty("account." + index + ".address", "");
                String subaccountStr = props.getProperty("account." + index + ".subaccountNumber", "0");
                int subaccountNumber = Integer.parseInt(subaccountStr.trim());
                
                if (accountName.isEmpty() || mnemonic.isEmpty() || address.isEmpty()) {
                    System.err.println("警告: 账户 " + index + " 配置不完整，跳过");
                    index++;
                    continue;
                }
                
                accounts.add(new AccountInfo(accountId, accountName, mnemonic, address, subaccountNumber));
                index++;
            }
            
        } catch (Exception e) {
            System.err.println("读取账户配置失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        return accounts;
    }
}

