-- 用户表：存储系统用户（交易员和管理员）
CREATE TABLE IF NOT EXISTS `users` (
    `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `username` VARCHAR(100) NOT NULL UNIQUE COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码（建议使用哈希存储）',
    `is_admin` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为超级管理员',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_username` (`username`),
    INDEX `idx_is_admin` (`is_admin`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 初始化超级管理员账号 trader1
INSERT INTO `users` (`username`, `password`, `is_admin`) 
VALUES ('trader1', 'trader1', TRUE)
ON DUPLICATE KEY UPDATE `is_admin` = TRUE;

-- 账户表：存储交易员的账户配置
CREATE TABLE IF NOT EXISTS `accounts` (
    `account_id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '全局账户ID（所有交易员共享，自增主键）',
    `username` VARCHAR(100) NOT NULL COMMENT '交易员用户名',
    `account_name` VARCHAR(200) NOT NULL COMMENT '账户名称',
    `mnemonic_phrase` TEXT NOT NULL COMMENT '助记词（加密存储）',
    `address` VARCHAR(200) NOT NULL COMMENT '钱包地址',
    `subaccount_number` INT NOT NULL DEFAULT 0 COMMENT '子账号编号',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户配置表';

-- Token 表：存储用户登录 token
CREATE TABLE IF NOT EXISTS `tokens` (
    `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `token` VARCHAR(200) NOT NULL UNIQUE COMMENT 'Token 字符串',
    `username` VARCHAR(100) NOT NULL COMMENT '用户名',
    `expire_time` BIGINT NOT NULL COMMENT '过期时间（毫秒时间戳）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_token` (`token`),
    INDEX `idx_username` (`username`),
    INDEX `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token 表';

-- 策略表：存储策略配置和状态
CREATE TABLE IF NOT EXISTS `strategies` (
    `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    `symbol_id` SMALLINT NOT NULL UNIQUE COMMENT '交易对ID（唯一）',
    `username` VARCHAR(100) NOT NULL COMMENT '创建策略的交易员用户名',
    `status` VARCHAR(20) NOT NULL DEFAULT 'STOPPED' COMMENT '策略状态：RUNNING, STOPPED',
    
    -- 账户配置（JSON 格式存储）
    `buy_account_ids` JSON COMMENT '买单账户ID数组',
    `sell_account_ids` JSON COMMENT '卖单账户ID数组',
    
    -- 价格配置
    `min_price_e8` BIGINT NOT NULL COMMENT '最小价格（E8格式）',
    `max_price_e8` BIGINT NOT NULL COMMENT '最大价格（E8格式）',
    `tick_size_e8` BIGINT NOT NULL COMMENT '价格步长（E8格式）',
    
    -- 策略参数
    `volatility_percent` DOUBLE NOT NULL COMMENT '波动率百分比',
    `base_asset_id` VARCHAR(50) NOT NULL COMMENT '基础资产ID',
    `quote_asset_id` VARCHAR(50) NOT NULL DEFAULT 'USDT' COMMENT '计价资产ID',
    `exchange_id` SMALLINT NOT NULL COMMENT '交易所ID',
    
    -- 时间配置（毫秒）
    `cycle_duration_ms` BIGINT NOT NULL COMMENT '周期时长（毫秒）',
    `target_volume_e8` BIGINT NOT NULL COMMENT '目标量（E8格式）',
    `trigger_interval_ms` BIGINT NOT NULL COMMENT '触发间隔（毫秒）',
    
    -- 其他配置
    `enable_volume_target` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用目标量',
    `maker_counts` INT NOT NULL DEFAULT 6 COMMENT 'Maker订单数量',
    `noise_factory` DOUBLE NOT NULL DEFAULT 0.5 COMMENT '噪声因子',
    
    -- 事件调度器配置（可选，毫秒）
    `min_interval_ms` BIGINT COMMENT '最小下单间隔（毫秒）',
    `max_interval_ms` BIGINT COMMENT '最大下单间隔（毫秒）',
    
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_symbol_id` (`symbol_id`),
    INDEX `idx_username` (`username`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略配置表';

