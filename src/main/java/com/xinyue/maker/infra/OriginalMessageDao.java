package com.xinyue.maker.infra;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原始消息 DAO，异步插入 MySQL 数据库。
 * <p>
 * 设计要点：
 * 1. 使用 HikariCP 连接池管理数据库连接
 * 2. 异步插入，不阻塞调用线程（热路径）
 * 3. 支持批量插入以提高性能
 * 4. 自动重试机制处理临时错误
 */
public final class OriginalMessageDao {

    private static final String DB_HOST = "8.222.188.124";
    private static final int DB_PORT = 13306;
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "cljslrl0620";
    // 数据库名（如果表在特定数据库中，可以设置；如果为空，则使用默认数据库）
    private static final String DB_NAME = "maker"; // 留空表示使用默认数据库，或设置为具体数据库名如 "maker_system"
    private static final String TABLE_NAME = "original_message";

    // SQL 语句会在构造函数中根据数据库名和表名动态构建
    private String insertSql;
    private String batchInsertSql;

    // 批量插入配置
    private static final int BATCH_SIZE = 10;
    private static final long BATCH_TIMEOUT_MS = 1000; // 1秒超时，即使未满批次也提交

    private final HikariDataSource dataSource;
    private final ExecutorService executorService;
    
    // 批量插入缓冲区（使用 LinkedBlockingQueue 保证 FIFO 顺序，确保插入顺序与接收顺序一致）
    private final BlockingQueue<MessageRecord> batchQueue;
    private final ScheduledExecutorService scheduler;
    
    // 统计信息
    private final AtomicLong totalInserted = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    /**
     * 消息记录，用于批量插入。
     */
    private static final class MessageRecord {
        final String messageType;
        final String exchange;
        final String message;
        final String symbol;
        final LocalDateTime createTime;

        MessageRecord(String messageType, String exchange, String message, String symbol, LocalDateTime createTime) {
            this.messageType = messageType;
            this.exchange = exchange;
            this.message = message;
            this.symbol = symbol;
            this.createTime = createTime;
        }
    }

    public OriginalMessageDao() {
        // 初始化连接池
        HikariConfig config = new HikariConfig();
        String jdbcUrl;
        if (DB_NAME != null && !DB_NAME.isEmpty()) {
            jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC", 
                    DB_HOST, DB_PORT, DB_NAME);
        } else {
            jdbcUrl = String.format("jdbc:mysql://%s:%d?useSSL=false&serverTimezone=UTC", 
                    DB_HOST, DB_PORT);
        }
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASSWORD);
        
        // 构建 SQL 语句
        this.insertSql = String.format(
                "INSERT INTO %s (message_type, exchange, message, symbol, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?)",
                TABLE_NAME);
        this.batchInsertSql = insertSql;
        config.setMaximumPoolSize(10); // 最大连接数
        config.setMinimumIdle(2); // 最小空闲连接数
        config.setConnectionTimeout(3000); // 连接超时 3 秒
        config.setIdleTimeout(600000); // 空闲超时 10 分钟
        config.setMaxLifetime(1800000); // 连接最大生命周期 30 分钟
        config.setLeakDetectionThreshold(60000); // 连接泄漏检测阈值 60 秒
        config.setAutoCommit(false); // 批量插入时手动提交
        
        this.dataSource = new HikariDataSource(config);
        
        // 异步执行线程池
        this.executorService = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "original-message-dao");
            t.setDaemon(true);
            return t;
        });
        
        // 批量插入缓冲区（FIFO 队列，保证插入顺序与接收顺序一致，无界队列避免阻塞调用方）
        this.batchQueue = new LinkedBlockingQueue<>();
        
        // 定时器，用于超时批量提交
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "original-message-batch-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 启动批量插入处理线程（单线程，保证顺序）
        startBatchProcessor();
        
        // 注意：不再使用定时器刷新，改为在批量处理线程中检查超时，确保严格的顺序
    }

    /**
     * 异步插入单条消息。
     * <p>
     * 此方法不会阻塞调用线程，立即返回。
     * <p>
     * 顺序保证：消息按照调用顺序添加到 FIFO 队列，单线程批量处理，确保数据库插入顺序与接收顺序一致。
     */
    public void insertAsync(String messageType, String exchange, String message, String symbol) {
        LocalDateTime now = LocalDateTime.now();
        MessageRecord record = new MessageRecord(messageType, exchange, message, symbol, now);
        
        // 添加到批量队列尾部（FIFO，保证顺序）
        if (!batchQueue.offer(record)) {
            // 队列满（理论上不会发生，因为是无界队列），直接执行单条插入
            executorService.submit(() -> insertSingle(record));
        }
    }

    /**
     * 启动批量插入处理线程。
     * <p>
     * 单线程处理，保证严格按照 FIFO 顺序插入数据库。
     */
    private void startBatchProcessor() {
        executorService.submit(() -> {
            List<MessageRecord> batch = new ArrayList<>(BATCH_SIZE);
            long lastFlushTime = System.currentTimeMillis();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 从队列头部按顺序取出记录（FIFO），最多等待 10ms 或达到批次大小
                    MessageRecord record = batchQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (record != null) {
                        batch.add(record); // 按顺序添加到批次列表
                    }
                    
                    long currentTime = System.currentTimeMillis();
                    boolean shouldFlush = false;
                    
                    // 如果达到批次大小，立即按顺序提交
                    if (batch.size() >= BATCH_SIZE) {
                        shouldFlush = true;
                    }
                    // 如果超时（1秒）且批次不为空，也提交（确保及时性）
                    else if (!batch.isEmpty() && (currentTime - lastFlushTime) >= BATCH_TIMEOUT_MS) {
                        shouldFlush = true;
                    }
                    
                    if (shouldFlush) {
                        insertBatch(batch);
                        batch.clear();
                        lastFlushTime = currentTime;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // 退出前处理剩余批次
                    if (!batch.isEmpty()) {
                        insertBatch(batch);
                    }
                    break;
                }
            }
        });
    }

    /**
     * 批量插入消息。
     * <p>
     * 注意：按照 records 列表的顺序插入，确保与接收顺序一致。
     */
    private void insertBatch(List<MessageRecord> records) {
        if (records.isEmpty()) {
            return;
        }

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(batchInsertSql)) {
                LocalDateTime now = LocalDateTime.now();
                
                // 按照列表顺序插入，保证顺序（列表顺序 = 队列 FIFO 顺序）
                for (MessageRecord record : records) {
                    ps.setString(1, record.messageType);
                    ps.setString(2, record.exchange);
                    ps.setString(3, record.message);
                    ps.setString(4, record.symbol);
                    ps.setTimestamp(5, Timestamp.valueOf(record.createTime != null ? record.createTime : now));
                    ps.setTimestamp(6, Timestamp.valueOf(now));
                    ps.addBatch();
                }
                
                int[] results = ps.executeBatch();
                conn.commit();
                
                int successCount = 0;
                for (int result : results) {
                    if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                        successCount++;
                    }
                }
                
                totalInserted.addAndGet(successCount);
                
                // 如果部分失败，记录日志（实际生产环境应使用异步日志）
                if (successCount < records.size()) {
                    totalFailed.addAndGet(records.size() - successCount);
                    System.err.println("批量插入部分失败: " + (records.size() - successCount) + " 条");
                }
            }
        } catch (SQLException e) {
            totalFailed.addAndGet(records.size());
            System.err.println("批量插入失败: " + e.getMessage());
            e.printStackTrace();
            
            // 失败时，按顺序单条插入（降级策略，保持顺序）
            // 注意：必须按顺序执行，不能并发，否则顺序会乱
            for (MessageRecord record : records) {
                insertSingle(record);
            }
        }
    }

    /**
     * 单条插入消息（降级策略）。
     */
    private void insertSingle(MessageRecord record) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                LocalDateTime now = LocalDateTime.now();
                
                ps.setString(1, record.messageType);
                ps.setString(2, record.exchange);
                ps.setString(3, record.message);
                ps.setString(4, record.symbol);
                ps.setTimestamp(5, Timestamp.valueOf(record.createTime != null ? record.createTime : now));
                ps.setTimestamp(6, Timestamp.valueOf(now));
                
                int rows = ps.executeUpdate();
                conn.commit();
                
                if (rows > 0) {
                    totalInserted.incrementAndGet();
                } else {
                    totalFailed.incrementAndGet();
                }
            }
        } catch (SQLException e) {
            totalFailed.incrementAndGet();
            System.err.println("单条插入失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 强制刷新批量缓冲区（超时触发或关闭时调用）。
     * <p>
     * 注意：此方法由定时器线程调用，可能与批量处理线程并发执行。
     * 为了保持顺序，这里只负责将队列中的记录取出，实际的插入操作由批量处理线程完成。
     * 或者，我们可以使用锁来确保顺序，但为了简化，这里暂时不刷新，让批量处理线程自然处理。
     */
    private void flushBatch() {
        // 注意：flushBatch 由定时器线程调用，可能与批量处理线程并发
        // 为了保持严格的顺序，这里不做任何操作，让批量处理线程自然处理
        // 批量处理线程的 poll 超时机制已经能够及时处理队列中的记录
    }

    /**
     * 获取统计信息。
     */
    public Stats getStats() {
        return new Stats(totalInserted.get(), totalFailed.get(), batchQueue.size());
    }

    /**
     * 统计信息。
     */
    public static final class Stats {
        private final long totalInserted;
        private final long totalFailed;
        private final int queueSize;

        Stats(long totalInserted, long totalFailed, int queueSize) {
            this.totalInserted = totalInserted;
            this.totalFailed = totalFailed;
            this.queueSize = queueSize;
        }

        public long totalInserted() {
            return totalInserted;
        }

        public long totalFailed() {
            return totalFailed;
        }

        public int queueSize() {
            return queueSize;
        }
    }

    /**
     * 关闭资源。
     */
    public void shutdown() {
        // 停止定时器
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 刷新剩余批次
        flushBatch();
        
        // 关闭线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭连接池
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
