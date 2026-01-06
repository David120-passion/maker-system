package com.xinyue.maker.web.service;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.sql.SqlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Token 管理器。
 * 负责 token 的生成、验证和失效，使用 MySQL 存储。
 */
@Component
public final class TokenManager {

    private static final Logger LOG = LoggerFactory.getLogger(TokenManager.class);

    @Inject
    private SqlUtils sqlUtils;

    // token 有效期：1小时（毫秒）
    private static final long TOKEN_EXPIRE_TIME = 3600 * 1000L;

    // 清理过期 token 的定时任务
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "token-cleanup");
        t.setDaemon(true);
        return t;
    });

    public TokenManager() {
        // 启动清理过期 token 的定时任务（每分钟清理一次）
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredTokens, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 添加 token。
     *
     * @param token token 字符串
     * @param username 用户名
     */
    public void addToken(String token, String username) {
        try {
            long expireTime = System.currentTimeMillis() + TOKEN_EXPIRE_TIME;
            sqlUtils.sql("INSERT INTO tokens (token, username, expire_time) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE username = ?, expire_time = ?",
                    token, username, expireTime, username, expireTime)
                    .update();
            LOG.debug("添加 token: username={}, expireTime={}", username, expireTime);
        } catch (SQLException e) {
            LOG.error("添加 token 失败", e);
            throw new RuntimeException("添加 token 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证 token 是否有效。
     *
     * @param token token 字符串
     * @return true 如果 token 有效，false 否则
     */
    public boolean isValidToken(String token) {
        try {
            Object expireTimeObj = sqlUtils.sql("SELECT expire_time FROM tokens WHERE token = ?", token)
                    .queryValue();
            if (expireTimeObj == null) {
                return false;
            }
            long expireTime = ((Number) expireTimeObj).longValue();
            // 检查是否过期
            if (System.currentTimeMillis() > expireTime) {
                // 删除过期 token
                sqlUtils.sql("DELETE FROM tokens WHERE token = ?", token).update();
                return false;
            }
            return true;
        } catch (SQLException e) {
            LOG.error("验证 token 失败", e);
            return false;
        }
    }

    /**
     * 获取 token 对应的用户名。
     *
     * @param token token 字符串
     * @return 用户名，如果 token 无效返回 null
     */
    public String getUsername(String token) {
        try {
            TokenInfo info = sqlUtils.sql(
                    "SELECT username, expire_time FROM tokens WHERE token = ?", token)
                    .queryRow(TokenInfo.class);
            if (info == null) {
                return null;
            }
            // 检查是否过期
            if (System.currentTimeMillis() > info.expire_time) {
                // 删除过期 token
                sqlUtils.sql("DELETE FROM tokens WHERE token = ?", token).update();
                return null;
            }
            return info.username;
        } catch (SQLException e) {
            LOG.error("获取用户名失败", e);
            return null;
        }
    }

    /**
     * 使 token 失效。
     *
     * @param token token 字符串
     */
    public void invalidateToken(String token) {
        try {
            sqlUtils.sql("DELETE FROM tokens WHERE token = ?", token).update();
            LOG.debug("token 已失效: token={}", token);
        } catch (SQLException e) {
            LOG.error("使 token 失效失败", e);
        }
    }

    /**
     * 清理过期的 token。
     */
    private void cleanupExpiredTokens() {
        try {
            long now = System.currentTimeMillis();
            int rows = sqlUtils.sql("DELETE FROM tokens WHERE expire_time < ?", now).update();
            if (rows > 0) {
                LOG.debug("清理过期 token: 共清理 {} 个", rows);
            }
        } catch (SQLException e) {
            LOG.error("清理过期 token 失败", e);
        }
    }

    /**
     * Token 信息（用于查询结果映射）。
     */
    private static class TokenInfo {
        public String username;
        public long expire_time;

        public TokenInfo() {
        }
    }
}
