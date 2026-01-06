package com.xinyue.maker.web.controller;

import com.xinyue.maker.web.service.TokenManager;
import com.xinyue.maker.web.service.UserService;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 认证控制器。
 * 提供登录、登出等认证相关接口。
 */
@Controller
@Mapping("/api/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    @Inject
    private TokenManager tokenManager;

    @Inject
    private UserService userService;

    /**
     * 登录接口。
     * POST /api/auth/login
     * 
     * 请求体示例：
     * {
     *   "username": "trader1",
     *   "password": "trader1"
     * }
     */
    @Post
    @Mapping("/login")
    public Map<String, Object> login(@Body Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String username = parseString(params.get("username"), "");
            String password = parseString(params.get("password"), "");
            
            // 验证用户名和密码
            if (username.isEmpty() || password.isEmpty()) {
                result.put("code", 400);
                result.put("message", "用户名和密码不能为空");
                result.put("success", false);
                return result;
            }
            
            // 从数据库验证用户
            UserService.UserInfo user = userService.authenticate(username, password);
            if (user == null) {
                result.put("code", 401);
                result.put("message", "用户名或密码错误");
                result.put("success", false);
                return result;
            }
            
            // 生成 token
            String token = generateToken(username);
            
            // 返回成功结果
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", username);
            data.put("isAdmin", user.isAdmin);
            data.put("expiresIn", 3600); // token 有效期（秒）
            
            result.put("code", 200);
            result.put("message", "登录成功");
            result.put("data", data);
            result.put("success", true);
            
            LOG.info("用户登录成功: username={}, isAdmin={}", username, user.isAdmin);
        } catch (SQLException e) {
            LOG.error("登录失败（数据库错误）", e);
            result.put("code", 500);
            result.put("message", "登录失败: " + e.getMessage());
            result.put("success", false);
        } catch (Exception e) {
            LOG.error("登录失败", e);
            result.put("code", 500);
            result.put("message", "登录失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 验证 token 接口。
     * POST /api/auth/verify
     * 
     * 请求体示例：
     * {
     *   "token": "xxx"
     * }
     */
    @Post
    @Mapping("/verify")
    public Map<String, Object> verify(@Body Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String token = parseString(params.get("token"), "");
            
            if (token.isEmpty()) {
                result.put("code", 400);
                result.put("message", "token 不能为空");
                result.put("success", false);
                return result;
            }
            
            // 验证 token
            boolean isValid = tokenManager.isValidToken(token);
            
            if (isValid) {
                result.put("code", 200);
                result.put("message", "token 有效");
                result.put("success", true);
            } else {
                result.put("code", 401);
                result.put("message", "token 无效或已过期");
                result.put("success", false);
            }
        } catch (Exception e) {
            LOG.error("验证 token 失败", e);
            result.put("code", 500);
            result.put("message", "验证失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 创建交易员账号（仅超级管理员）。
     * POST /api/auth/create-user
     * 
     * 请求头：Authorization: Bearer {token}
     * 请求体：
     * {
     *   "username": "trader2",
     *   "password": "password123",
     *   "isAdmin": false
     * }
     */
    @Post
    @Mapping("/create-user")
    public Map<String, Object> createUser(@Header("Authorization") String authorization,
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
                result.put("message", "权限不足，只有超级管理员可以创建用户");
                result.put("success", false);
                return result;
            }

            // 解析参数
            String username = parseString(params.get("username"), "");
            String password = parseString(params.get("password"), "");
            Boolean isAdmin = parseBoolean(params.get("isAdmin"), false);

            // 验证必填字段
            if (username.isEmpty() || password.isEmpty()) {
                result.put("code", 400);
                result.put("message", "用户名和密码不能为空");
                result.put("success", false);
                return result;
            }

            // 创建用户
            UserService.UserInfo user = userService.createUser(username, password, isAdmin);

            Map<String, Object> data = new HashMap<>();
            data.put("id", user.id);
            data.put("username", user.username);
            data.put("isAdmin", user.isAdmin);
            // 不返回密码（安全考虑）

            result.put("code", 200);
            result.put("message", "用户创建成功");
            result.put("data", data);
            result.put("success", true);
        } catch (IllegalArgumentException e) {
            LOG.warn("创建用户失败: {}", e.getMessage());
            result.put("code", 400);
            result.put("message", e.getMessage());
            result.put("success", false);
        } catch (SQLException e) {
            LOG.error("创建用户失败（数据库错误）", e);
            result.put("code", 500);
            result.put("message", "创建用户失败: " + e.getMessage());
            result.put("success", false);
        } catch (Exception e) {
            LOG.error("创建用户失败", e);
            result.put("code", 500);
            result.put("message", "创建用户失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 获取所有交易员列表（仅超级管理员）。
     * GET /api/auth/users
     * 
     * 请求头：Authorization: Bearer {token}
     */
    @Get
    @Mapping("/users")
    public Map<String, Object> getAllUsers(@Header("Authorization") String authorization) {
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
                result.put("message", "权限不足，只有超级管理员可以查看所有用户");
                result.put("success", false);
                return result;
            }

            // 获取所有用户列表
            List<UserService.UserInfo> users = userService.getAllUsers();
            
            List<Map<String, Object>> userList = users.stream().map(user -> {
                Map<String, Object> userMap = new HashMap<>();
                userMap.put("id", user.id);
                userMap.put("username", user.username);
                userMap.put("isAdmin", user.isAdmin);
                // 不返回密码（安全考虑）
                return userMap;
            }).collect(Collectors.toList());

            result.put("code", 200);
            result.put("data", userList);
            result.put("count", userList.size());
            result.put("success", true);
        } catch (SQLException e) {
            LOG.error("获取用户列表失败（数据库错误）", e);
            result.put("code", 500);
            result.put("message", "获取用户列表失败: " + e.getMessage());
            result.put("success", false);
        } catch (Exception e) {
            LOG.error("获取用户列表失败", e);
            result.put("code", 500);
            result.put("message", "获取用户列表失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 登出接口。
     * POST /api/auth/logout
     */
    @Post
    @Mapping("/logout")
    public Map<String, Object> logout(@Body Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String token = parseString(params.get("token"), "");
            
            if (!token.isEmpty()) {
                tokenManager.invalidateToken(token);
                LOG.info("用户登出: token={}", token);
            }
            
            result.put("code", 200);
            result.put("message", "登出成功");
            result.put("success", true);
        } catch (Exception e) {
            LOG.error("登出失败", e);
            result.put("code", 500);
            result.put("message", "登出失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }

    /**
     * 生成 token。
     */
    private String generateToken(String username) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenManager.addToken(token, username);
        return token;
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

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}

