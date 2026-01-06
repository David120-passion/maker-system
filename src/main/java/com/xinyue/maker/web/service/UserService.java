package com.xinyue.maker.web.service;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.sql.SqlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

/**
 * 用户服务。
 * 管理用户认证和权限。
 */
@Component
public final class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    @Inject
    private SqlUtils sqlUtils;

    /**
     * 用户信息模型。
     */
    public static class UserInfo {
        public int id;
        public String username;
        public String password;
        public boolean isAdmin;

        public UserInfo() {
        }

        public UserInfo(int id, String username, String password, boolean isAdmin) {
            this.id = id;
            this.username = username;
            this.password = password;
            this.isAdmin = isAdmin;
        }
    }

    /**
     * 根据用户名和密码验证用户。
     *
     * @param username 用户名
     * @param password 密码
     * @return 用户信息，如果验证失败返回 null
     */
    public UserInfo authenticate(String username, String password) throws SQLException {
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return null;
        }

        UserInfo user = getUserByUsername(username);
        if (user == null) {
            LOG.warn("用户不存在: username={}", username);
            return null;
        }

        // 简单密码验证（后续可以改为哈希验证）
        if (!password.equals(user.password)) {
            LOG.warn("密码错误: username={}", username);
            return null;
        }

        LOG.debug("用户验证成功: username={}, isAdmin={}", username, user.isAdmin);
        return user;
    }

    /**
     * 根据用户名获取用户信息。
     *
     * @param username 用户名
     * @return 用户信息，如果不存在返回 null
     */
    public UserInfo getUserByUsername(String username) throws SQLException {
        if (username == null || username.isEmpty()) {
            return null;
        }

        UserInfo user = sqlUtils.sql(
                "SELECT id, username, password, is_admin AS isAdmin " +
                        "FROM users WHERE username = ?",
                username)
                .queryRow(UserInfo.class);

        return user;
    }

    /**
     * 检查用户是否为超级管理员。
     *
     * @param username 用户名
     * @return true 如果是超级管理员，false 否则
     */
    public boolean isAdmin(String username) throws SQLException {
        UserInfo user = getUserByUsername(username);
        return user != null && user.isAdmin;
    }

    /**
     * 创建新用户。
     *
     * @param username 用户名
     * @param password 密码
     * @param isAdmin 是否为超级管理员
     * @return 创建的用户信息
     */
    public UserInfo createUser(String username, String password, boolean isAdmin) throws SQLException {
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        // 检查用户名是否已存在
        UserInfo existing = getUserByUsername(username);
        if (existing != null) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }

        // 插入新用户
        Number userId = sqlUtils.sql(
                "INSERT INTO users (username, password, is_admin) VALUES (?, ?, ?)",
                username, password, isAdmin)
                .updateReturnKey();

        if (userId == null) {
            throw new SQLException("创建用户失败，未返回自增ID");
        }

        LOG.info("创建用户成功: username={}, isAdmin={}", username, isAdmin);
        return new UserInfo(userId.intValue(), username, password, isAdmin);
    }

    /**
     * 更新用户密码。
     *
     * @param username 用户名
     * @param newPassword 新密码
     * @return true 如果更新成功，false 如果用户不存在
     */
    public boolean updatePassword(String username, String newPassword) throws SQLException {
        if (username == null || username.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            throw new IllegalArgumentException("用户名和新密码不能为空");
        }

        int rows = sqlUtils.sql(
                "UPDATE users SET password = ? WHERE username = ?",
                newPassword, username)
                .update();

        if (rows > 0) {
            LOG.info("更新密码成功: username={}", username);
            return true;
        }
        return false;
    }

    /**
     * 删除用户。
     *
     * @param username 用户名
     * @return true 如果删除成功，false 如果用户不存在
     */
    public boolean deleteUser(String username) throws SQLException {
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }

        int rows = sqlUtils.sql(
                "DELETE FROM users WHERE username = ?",
                username)
                .update();

        if (rows > 0) {
            LOG.info("删除用户成功: username={}", username);
            return true;
        }
        return false;
    }

    /**
     * 获取所有用户列表（超级管理员用）。
     *
     * @return 所有用户列表
     */
    public List<UserInfo> getAllUsers() throws SQLException {
        List<UserInfo> users = sqlUtils.sql(
                "SELECT id, username, password, is_admin AS isAdmin FROM users ORDER BY id")
                .queryRowList(UserInfo.class);
        return users != null ? users : new java.util.ArrayList<>();
    }
}

