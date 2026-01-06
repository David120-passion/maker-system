# OriginalMessageDao 使用说明

## 概述

`OriginalMessageDao` 是一个异步插入 MySQL 数据库的 DAO 类，用于保存原始消息数据。它设计用于 HFT 系统，确保不阻塞热路径（L2 核心层）。

## 特性

- ✅ **异步插入**：不阻塞调用线程，立即返回
- ✅ **批量插入**：自动批量处理，提高性能（默认批次大小：100）
- ✅ **超时刷新**：即使未达到批次大小，1秒后也会自动提交
- ✅ **连接池**：使用 HikariCP 管理数据库连接
- ✅ **错误处理**：批量插入失败时自动降级为单条插入
- ✅ **统计信息**：提供插入成功/失败统计

## 数据库配置

在 `OriginalMessageDao.java` 中配置数据库连接信息：

```java
private static final String DB_HOST = "8.222.188.124";
private static final int DB_PORT = 13306;
private static final String DB_USER = "root";
private static final String DB_PASSWORD = "cljslrl0620";
private static final String DB_NAME = ""; // 留空使用默认数据库，或设置为具体数据库名
private static final String TABLE_NAME = "original_message";
```

## 表结构

```sql
CREATE TABLE original_message (
    message_type CHAR(50),
    exchange CHAR(50),
    message JSON,
    symbol VARCHAR(100),
    create_time DATETIME,
    update_time DATETIME
);
```

## 使用方法

### 1. 创建 DAO 实例

```java
OriginalMessageDao dao = new OriginalMessageDao();
```

### 2. 异步插入消息

```java
// 异步插入，不会阻塞
dao.insertAsync(
    "DEPTH_UPDATE",           // message_type
    "BINANCE",                // exchange
    "{\"bids\":[[...]]}",     // message (JSON 字符串)
    "BTCUSDT"                 // symbol
);
```

### 3. 获取统计信息

```java
OriginalMessageDao.Stats stats = dao.getStats();
System.out.println("已插入: " + stats.totalInserted());
System.out.println("失败: " + stats.totalFailed());
System.out.println("队列大小: " + stats.queueSize());
```

### 4. 关闭资源（应用关闭时）

```java
dao.shutdown();
```

## 集成到 Normalizer

可以在 `Normalizer.onJsonMessage()` 方法中调用 DAO：

```java
public class Normalizer {
    private final OriginalMessageDao messageDao;
    
    public Normalizer(RingBuffer<CoreEvent> ringBuffer, OriginalMessageDao messageDao) {
        this.ringBuffer = ringBuffer;
        this.messageDao = messageDao;
        // ...
    }
    
    public void onJsonMessage(Exchange exchange, byte[] payload) {
        // 保存原始消息到数据库（异步，不阻塞）
        String messageJson = new String(payload, StandardCharsets.UTF_8);
        String symbol = extractSymbol(payload); // 从 payload 中提取 symbol
        String messageType = extractMessageType(payload); // 从 payload 中提取类型
        
        messageDao.insertAsync(
            messageType,
            exchange.name(),
            messageJson,
            symbol
        );
        
        // 继续处理逻辑...
    }
}
```

## 性能优化

- **批量插入**：默认每 100 条记录批量提交一次
- **超时刷新**：即使未达到批次大小，1秒后也会自动提交
- **连接池**：使用 HikariCP，最大 10 个连接，最小 2 个空闲连接
- **无界队列**：使用 `LinkedBlockingQueue`，避免阻塞调用方

## 注意事项

1. **数据库名**：如果表在特定数据库中，请设置 `DB_NAME` 常量；如果使用默认数据库，留空即可
2. **线程安全**：`insertAsync()` 方法是线程安全的，可以并发调用
3. **资源清理**：应用关闭时务必调用 `shutdown()` 方法，确保所有批次都被刷新
4. **错误处理**：批量插入失败时会自动降级为单条插入，确保数据不丢失

## 依赖

已添加到 `pom.xml`：

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <version>8.0.33</version>
</dependency>

<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

