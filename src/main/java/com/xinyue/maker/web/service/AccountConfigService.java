package com.xinyue.maker.web.service;

import com.xinyue.maker.web.model.AccountInfo;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.sql.SqlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 账号配置服务。
 * 按交易员隔离管理账号配置，使用 MySQL 存储。
 */
@Component
public final class AccountConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountConfigService.class);

    @Inject
    private SqlUtils sqlUtils;

    /**
     * 添加账号。
     *
     * @param username 交易员用户名
     * @param accountName 账号名称
     * @param mnemonicPhrase 助记词
     * @param address 地址
     * @param subaccountNumber 子账号编号
     * @return 创建的账号信息
     */
    public AccountInfo addAccount(String username, String accountName, String mnemonicPhrase,
                                  String address, int subaccountNumber) throws SQLException {
        // 插入新账号（account_id 由数据库自增生成）
        Number accountIdObj = sqlUtils.sql("INSERT INTO accounts (username, account_name, mnemonic_phrase, address, subaccount_number) " +
                        "VALUES (?, ?, ?, ?, ?)",
                username, accountName, mnemonicPhrase, address, subaccountNumber)
                .updateReturnKey();

        if (accountIdObj == null) {
            throw new SQLException("插入账号失败，未返回自增ID");
        }

        int accountId = accountIdObj.intValue();
        LOG.info("添加账号: username={}, accountId={}, accountName={}", username, accountId, accountName);
        return new AccountInfo(accountId, accountName, mnemonicPhrase, address, subaccountNumber);
    }

    /**
     * 获取交易员的所有账号。
     *
     * @param username 交易员用户名
     * @return 账号列表
     */
    public List<AccountInfo> getAccounts(String username) throws SQLException {
        List<AccountInfo> accounts = sqlUtils.sql(
                "SELECT account_id AS accountId, account_name AS accountName, " +
                        "mnemonic_phrase AS mnemonicPhrase, address, subaccount_number AS subaccountNumber " +
                        "FROM accounts WHERE username = ? ORDER BY account_id",
                username)
                .queryRowList(AccountInfo.class);
        return accounts != null ? accounts : new ArrayList<>();
    }

    /**
     * 根据 accountId 获取账号。
     *
     * @param username 交易员用户名
     * @param accountId 账号 ID
     * @return 账号信息，如果不存在返回 null
     */
    public AccountInfo getAccount(String username, int accountId) throws SQLException {
        return sqlUtils.sql(
                "SELECT account_id AS accountId, account_name AS accountName, " +
                        "mnemonic_phrase AS mnemonicPhrase, address, subaccount_number AS subaccountNumber " +
                        "FROM accounts WHERE username = ? AND account_id = ?",
                username, accountId)
                .queryRow(AccountInfo.class);
    }

    /**
     * 更新账号。
     *
     * @param username 交易员用户名
     * @param accountId 账号 ID
     * @param accountName 账号名称
     * @param mnemonicPhrase 助记词
     * @param address 地址
     * @param subaccountNumber 子账号编号
     * @return 更新后的账号信息，如果账号不存在返回 null
     */
    public AccountInfo updateAccount(String username, int accountId, String accountName,
                                    String mnemonicPhrase, String address, int subaccountNumber) throws SQLException {
        int rows = sqlUtils.sql(
                "UPDATE accounts SET account_name = ?, mnemonic_phrase = ?, address = ?, subaccount_number = ? " +
                        "WHERE username = ? AND account_id = ?",
                accountName, mnemonicPhrase, address, subaccountNumber, username, accountId)
                .update();

        if (rows == 0) {
            return null;
        }

        LOG.info("更新账号: username={}, accountId={}", username, accountId);
        return new AccountInfo(accountId, accountName, mnemonicPhrase, address, subaccountNumber);
    }

    /**
     * 删除账号。
     *
     * @param username 交易员用户名
     * @param accountId 账号 ID
     * @return true 如果删除成功，false 如果账号不存在
     */
    public boolean deleteAccount(String username, int accountId) throws SQLException {
        int rows = sqlUtils.sql(
                "DELETE FROM accounts WHERE username = ? AND account_id = ?",
                username, accountId)
                .update();

        if (rows > 0) {
            LOG.info("删除账号: username={}, accountId={}", username, accountId);
            return true;
        }
        return false;
    }

    /**
     * 根据 accountId 获取账号（不验证username，用于超级管理员操作）。
     *
     * @param accountId 账号 ID
     * @return 账号信息，如果不存在返回 null
     */
    public AccountInfo getAccountByAccountId(int accountId) throws SQLException {
        return sqlUtils.sql(
                "SELECT account_id AS accountId, account_name AS accountName, " +
                        "mnemonic_phrase AS mnemonicPhrase, address, subaccount_number AS subaccountNumber, username " +
                        "FROM accounts WHERE account_id = ?",
                accountId)
                .queryRow(AccountInfo.class);
    }

    /**
     * 分配账号给交易员。
     * 允许将账号分配给交易员，如果账号已被分配给其他交易员，将重新分配。
     *
     * @param accountId 账号 ID
     * @param username 交易员用户名
     * @return 更新后的账号信息，如果账号不存在返回 null
     */
    public AccountInfo assignAccountToTrader(int accountId, String username) throws SQLException {
        // 检查账号是否存在
        AccountInfo existingAccount = getAccountByAccountId(accountId);
        if (existingAccount == null) {
            return null;
        }

        // 记录之前的分配情况（如果有）
        String previousOwner = existingAccount.username;
        boolean isReassignment = previousOwner != null && !previousOwner.isEmpty() 
                && !previousOwner.equals(username);

        // 更新账号的username（允许重新分配）
        int rows = sqlUtils.sql(
                "UPDATE accounts SET username = ? WHERE account_id = ?",
                username, accountId)
                .update();

        if (rows == 0) {
            return null;
        }

        if (isReassignment) {
            LOG.info("重新分配账号: accountId={}, 从 {} 重新分配给 {}", accountId, previousOwner, username);
        } else {
            LOG.info("分配账号给交易员: accountId={}, username={}", accountId, username);
        }
        
        AccountInfo updated = getAccountByAccountId(accountId);
        return updated;
    }

    /**
     * 获取所有账号（超级管理员用）。
     *
     * @return 所有账号列表
     */
    public List<AccountInfo> getAllAccounts() throws SQLException {
        List<AccountInfo> accounts = sqlUtils.sql(
                "SELECT account_id AS accountId, account_name AS accountName, " +
                        "mnemonic_phrase AS mnemonicPhrase, address, subaccount_number AS subaccountNumber, username " +
                        "FROM accounts ORDER BY account_id")
                .queryRowList(AccountInfo.class);
        return accounts != null ? accounts : new ArrayList<>();
    }

    /**
     * 添加账号（超级管理员用，不指定username，账号创建后需要分配）。
     *
     * @param accountName 账号名称
     * @param mnemonicPhrase 助记词
     * @param address 地址
     * @param subaccountNumber 子账号编号
     * @return 创建的账号信息
     */
    public AccountInfo addAccountAdmin(String accountName, String mnemonicPhrase,
                                       String address, int subaccountNumber) throws SQLException {
        // 插入新账号（不指定username，由超级管理员后续分配）
        Number accountIdObj = sqlUtils.sql("INSERT INTO accounts (username, account_name, mnemonic_phrase, address, subaccount_number) " +
                        "VALUES (NULL, ?, ?, ?, ?)",
                accountName, mnemonicPhrase, address, subaccountNumber)
                .updateReturnKey();

        if (accountIdObj == null) {
            throw new SQLException("插入账号失败，未返回自增ID");
        }

        int accountId = accountIdObj.intValue();
        LOG.info("添加账号（管理员）: accountId={}, accountName={}", accountId, accountName);
        return new AccountInfo(accountId, accountName, mnemonicPhrase, address, subaccountNumber);
    }

    /**
     * 更新账号（超级管理员用，可以更新任意账号）。
     *
     * @param accountId 账号 ID
     * @param accountName 账号名称
     * @param mnemonicPhrase 助记词
     * @param address 地址
     * @param subaccountNumber 子账号编号
     * @return 更新后的账号信息，如果账号不存在返回 null
     */
    public AccountInfo updateAccountAdmin(int accountId, String accountName,
                                         String mnemonicPhrase, String address, int subaccountNumber) throws SQLException {
        int rows = sqlUtils.sql(
                "UPDATE accounts SET account_name = ?, mnemonic_phrase = ?, address = ?, subaccount_number = ? " +
                        "WHERE account_id = ?",
                accountName, mnemonicPhrase, address, subaccountNumber, accountId)
                .update();

        if (rows == 0) {
            return null;
        }

        LOG.info("更新账号（管理员）: accountId={}", accountId);
        return getAccountByAccountId(accountId);
    }

    /**
     * 删除账号（超级管理员用，可以删除任意账号）。
     *
     * @param accountId 账号 ID
     * @return true 如果删除成功，false 如果账号不存在
     */
    public boolean deleteAccountAdmin(int accountId) throws SQLException {
        int rows = sqlUtils.sql(
                "DELETE FROM accounts WHERE account_id = ?",
                accountId)
                .update();

        if (rows > 0) {
            LOG.info("删除账号（管理员）: accountId={}", accountId);
            return true;
        }
        return false;
    }

    /**
     * 获取所有交易员用户名列表。
     *
     * @return 交易员用户名集合
     */
    public Set<String> getAllTraders() throws SQLException {
        List<Object> usernameObjs = sqlUtils.sql("SELECT DISTINCT username FROM accounts WHERE username IS NOT NULL")
                .queryValueList();
        Set<String> usernames = new HashSet<>();
        if (usernameObjs != null) {
            for (Object obj : usernameObjs) {
                if (obj != null) {
                    usernames.add(obj.toString());
                }
            }
        }
        return usernames;
    }

}
