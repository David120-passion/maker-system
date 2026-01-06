package com.xinyue.maker.web.controller;

import com.xinyue.maker.io.rest.DydxRestClient;
import com.xinyue.maker.web.context.AppContext;
import com.xinyue.maker.web.model.AccountInfo;
import com.xinyue.maker.web.service.AccountConfigService;
import com.xinyue.maker.web.service.TokenManager;
import com.xinyue.maker.web.service.UserService;
import java.sql.SQLException;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Put;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 账号配置控制器。
 * 提供账号的增删改查接口，按交易员隔离。
 */
@Controller
@Mapping("/api/accounts")
public class AccountController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);
    
    @Inject
    private AccountConfigService accountService;
    
    @Inject
    private TokenManager tokenManager;

    @Inject
    private UserService userService;
    
    @Inject
    private AppContext appContext;
    
    @Inject
    private com.xinyue.maker.web.service.ThreadPoolService threadPoolService;

    /**
     * 添加账号。
     * POST /api/accounts
     * 
     * 请求头：Authorization: Bearer {token}
     * 请求体：
     * {
     *   "accountName": "账户1",
     *   "mnemonicPhrase": "word1 word2 ...",
     *   "address": "0x...",
     *   "subaccountNumber": 0
     * }
     */
    @Post
    @Mapping("")
    public Map<String, Object> addAccount(@Header("Authorization") String authorization,
                                          @Body Map<String, Object> params) {
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

            // 解析参数
            String accountName = parseString(params.get("accountName"), "");
            String mnemonicPhrase = parseString(params.get("mnemonicPhrase"), "");
            String address = parseString(params.get("address"), "");
            int subaccountNumber = parseInt(params.get("subaccountNumber"), 0);

            // 验证必填字段
            if (accountName.isEmpty() || mnemonicPhrase.isEmpty() || address.isEmpty()) {
                result.put("code", 400);
                result.put("message", "accountName、mnemonicPhrase 和 address 不能为空");
                result.put("success", false);
                return result;
            }

            // 检查是否为超级管理员
            if (!userService.isAdmin(username)) {
                result.put("code", 403);
                result.put("message", "权限不足，只有超级管理员可以添加账号");
                result.put("success", false);
                return result;
            }

            // 添加账号（超级管理员添加时不指定username，需要后续分配）
            AccountInfo account = accountService.addAccountAdmin(
                    accountName, mnemonicPhrase, address, subaccountNumber);

            Map<String, Object> data = new HashMap<>();
            data.put("accountId", account.accountId);
            data.put("accountName", account.accountName);
            data.put("address", account.address);
            data.put("subaccountNumber", account.subaccountNumber);
            // 不返回助记词（安全考虑）

            result.put("code", 200);
            result.put("message", "账号添加成功");
            result.put("data", data);
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("添加账号失败", e);
            result.put("code", 500);
            result.put("message", "添加账号失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 获取所有账号。
     * GET /api/accounts
     * 
     * 请求头：Authorization: Bearer {token}
     */
    @Get
    @Mapping("")
    public Map<String, Object> getAccounts(@Header("Authorization") String authorization) {
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

            // 获取账号列表
            List<AccountInfo> accounts = accountService.getAccounts(username);
            
            List<Map<String, Object>> accountList = accounts.stream().map(acc -> {
                Map<String, Object> accMap = new HashMap<>();
                accMap.put("accountId", acc.accountId);
                accMap.put("accountName", acc.accountName);
                accMap.put("address", acc.address);
                accMap.put("subaccountNumber", acc.subaccountNumber);
                // 不返回助记词（安全考虑）
                return accMap;
            }).collect(Collectors.toList());

            result.put("code", 200);
            result.put("data", accountList);
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("获取账号列表失败", e);
            result.put("code", 500);
            result.put("message", "获取账号列表失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 更新账号。
     * PUT /api/accounts/{accountId}
     */
    @Put
    @Mapping("/{accountId}")
    public Map<String, Object> updateAccount(@Header("Authorization") String authorization,
                                             int accountId,
                                             @Body Map<String, Object> params) {
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

            // 解析参数
            String accountName = parseString(params.get("accountName"), "");
            String mnemonicPhrase = parseString(params.get("mnemonicPhrase"), "");
            String address = parseString(params.get("address"), "");
            int subaccountNumber = parseInt(params.get("subaccountNumber"), 0);

            // 检查是否为超级管理员
            if (!userService.isAdmin(username)) {
                result.put("code", 403);
                result.put("message", "权限不足，只有超级管理员可以更新账号");
                result.put("success", false);
                return result;
            }

            // 更新账号（超级管理员可以更新任意账号）
            AccountInfo account = accountService.updateAccountAdmin(
                    accountId, accountName, mnemonicPhrase, address, subaccountNumber);

            if (account == null) {
                result.put("code", 404);
                result.put("message", "账号不存在");
                result.put("success", false);
                return result;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("accountId", account.accountId);
            data.put("accountName", account.accountName);
            data.put("address", account.address);
            data.put("subaccountNumber", account.subaccountNumber);

            result.put("code", 200);
            result.put("message", "账号更新成功");
            result.put("data", data);
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("更新账号失败", e);
            result.put("code", 500);
            result.put("message", "更新账号失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 删除账号。
     * DELETE /api/accounts/{accountId}
     */
    @Delete
    @Mapping("/{accountId}")
    public Map<String, Object> deleteAccount(@Header("Authorization") String authorization,
                                             int accountId) {
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

            // 检查是否为超级管理员
            if (!userService.isAdmin(username)) {
                result.put("code", 403);
                result.put("message", "权限不足，只有超级管理员可以删除账号");
                result.put("success", false);
                return result;
            }

            // 删除账号（超级管理员可以删除任意账号）
            boolean deleted = accountService.deleteAccountAdmin(accountId);

            if (!deleted) {
                result.put("code", 404);
                result.put("message", "账号不存在");
                result.put("success", false);
                return result;
            }

            result.put("code", 200);
            result.put("message", "账号删除成功");
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("删除账号失败", e);
            result.put("code", 500);
            result.put("message", "删除账号失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 分配账号给交易员。
     * POST /api/accounts/assign
     * 
     * 请求头：Authorization: Bearer {token}
     * 请求体：
     * {
     *   "accountId": 1,
     *   "username": "trader2"
     * }
     */
    @Post
    @Mapping("/assign")
    public Map<String, Object> assignAccount(@Header("Authorization") String authorization,
                                             @Body Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 验证 token 并获取用户名
            String currentUsername = getUsernameFromToken(authorization);
            if (currentUsername == null) {
                result.put("code", 401);
                result.put("message", "未授权，请先登录");
                result.put("success", false);
                return result;
            }

            // 检查是否为超级管理员
            if (!userService.isAdmin(currentUsername)) {
                result.put("code", 403);
                result.put("message", "权限不足，只有超级管理员可以分配账号");
                result.put("success", false);
                return result;
            }

            // 解析参数
            int accountId = parseInt(params.get("accountId"), 0);
            String username = parseString(params.get("username"), "");

            // 验证必填字段
            if (accountId <= 0 || username.isEmpty()) {
                result.put("code", 400);
                result.put("message", "accountId 和 username 不能为空");
                result.put("success", false);
                return result;
            }

            // 检查目标用户是否存在
            UserService.UserInfo targetUser = userService.getUserByUsername(username);
            if (targetUser == null) {
                result.put("code", 404);
                result.put("message", "目标交易员不存在: " + username);
                result.put("success", false);
                return result;
            }

            // 分配账号给交易员
            AccountInfo account = accountService.assignAccountToTrader(accountId, username);

            if (account == null) {
                result.put("code", 404);
                result.put("message", "账号不存在");
                result.put("success", false);
                return result;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("accountId", account.accountId);
            data.put("accountName", account.accountName);
            data.put("username", account.username);
            data.put("address", account.address);
            data.put("subaccountNumber", account.subaccountNumber);

            result.put("code", 200);
            result.put("message", "账号分配成功");
            result.put("data", data);
            result.put("success", true);
        } catch (SQLException e) {
            LOG.error("分配账号失败", e);
            result.put("code", 500);
            result.put("message", "分配账号失败: " + e.getMessage());
            result.put("success", false);
        } catch (Exception e) {
            LOG.error("分配账号失败", e);
            result.put("code", 500);
            result.put("message", "分配账号失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 批量查询账户余额。
     * POST /api/accounts/batch-balances
     * 
     * 请求头：Authorization: Bearer {token}
     * 请求体：
     * {
     *   "addresses": ["address1", "address2", "address3"]
     * }
     */
    @Post
    @Mapping("/batch-balances")
    public Map<String, Object> batchQueryBalances(@Header("Authorization") String authorization,
                                                   @Body Map<String, Object> params) {
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

            // 解析参数
            @SuppressWarnings("unchecked")
            List<String> addressList = (List<String>) params.get("addresses");
            if (addressList == null || addressList.isEmpty()) {
                result.put("code", 400);
                result.put("message", "addresses 不能为空");
                result.put("success", false);
                return result;
            }

            // subaccountNumber 写死为 1
            int subaccountNumber = 1;
            
            // 使用公共线程池并行查询
            java.util.concurrent.ConcurrentHashMap<String, DydxRestClient.AssetInfo[]> balances = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            
            // 获取公共线程池
            java.util.concurrent.ExecutorService executor = threadPoolService.getBalanceQueryExecutor();
            
            // 为每个地址提交查询任务到公共线程池
            for (String address : addressList) {
                java.util.concurrent.Future<?> future = executor.submit(() -> {
                    try {
                        // 每个线程创建独立的 DydxRestClient 实例（避免并发问题）
                        DydxRestClient dydxRestClient = new DydxRestClient();
                        
                        // 1. 调用 dYdX API 获取账户信息
                        String json = dydxRestClient.getSubaccount(address, subaccountNumber);
                        
                        // 2. 解析资产信息
                        DydxRestClient.AssetInfo[] assetInfos = dydxRestClient.parseAssetPositions(json);
                        
                        // 3. 存入结果 Map（使用 ConcurrentHashMap，线程安全）
                        balances.put(address, assetInfos);
                    } catch (Exception e) {
                        LOG.error("批量查询余额失败: address={}, subaccountNumber={}", address, subaccountNumber, e);
                        // 失败时返回空数组，不中断整个批量查询
                        balances.put(address, new DydxRestClient.AssetInfo[0]);
                    }
                });
                futures.add(future);
            }
            
            // 等待所有任务完成
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get(); // 等待任务完成，如果失败会抛出异常（已在任务内部捕获）
                } catch (Exception e) {
                    LOG.error("等待查询任务完成时发生错误", e);
                }
            }


            result.put("code", 200);
            result.put("message", "批量查询余额成功");
            result.put("data", balances);
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("批量查询余额失败", e);
            result.put("code", 500);
            result.put("message", "批量查询余额失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 获取所有账号（超级管理员用）。
     * GET /api/accounts/all
     * 
     * 请求头：Authorization: Bearer {token}
     */
    @Get
    @Mapping("/all")
    public Map<String, Object> getAllAccounts(@Header("Authorization") String authorization) {
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

            // 检查是否为超级管理员
            if (!userService.isAdmin(username)) {
                result.put("code", 403);
                result.put("message", "权限不足，只有超级管理员可以查看所有账号");
                result.put("success", false);
                return result;
            }

            // 获取所有账号列表
            List<AccountInfo> accounts = accountService.getAllAccounts();
            
            List<Map<String, Object>> accountList = accounts.stream().map(acc -> {
                Map<String, Object> accMap = new HashMap<>();
                accMap.put("accountId", acc.accountId);
                accMap.put("accountName", acc.accountName);
                accMap.put("address", acc.address);
                accMap.put("subaccountNumber", acc.subaccountNumber);
                accMap.put("username", acc.username);  // 显示所属交易员
                // 不返回助记词（安全考虑）
                return accMap;
            }).collect(Collectors.toList());

            result.put("code", 200);
            result.put("data", accountList);
            result.put("count", accountList.size());
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("获取所有账号列表失败", e);
            result.put("code", 500);
            result.put("message", "获取账号列表失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
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

    private String parseString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
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
}

