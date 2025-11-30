package com.xinyue.maker.common;

/**
 * L2 热路径中流转的核心事件载体 (God Object).
 * <p>
 * 特性：
 * 1. 预分配内存 (Zero GC)
 * 2. 联合体模式 (Union Pattern): 一个对象复用于行情、交易、风控等多种场景
 * 3. 公有字段 (Direct Access): 减少方法调用开销
 */
public final class CoreEvent {

    // === 元数据 (Metadata) ===
    public long timestamp;      // 事件发生时间 (Exchange TS)
    public long recvTime;       // 网关接收时间 (Local TS)
    public long sequence;       // 消息序号 (用于丢包检测)
    public CoreEventType type;  // 事件类型 (枚举引用不仅也是无GC的，只要不中途创建)

    // === 路由信息 (Routing) ===
    public short exchangeId;    // 交易所 ID
    public short symbolId;      // 币种 ID
    public short accountId;     // 账户 ID (0 表示公共行情)

    // === 行情数据 (Market Data Payload) ===
    // 针对 Ticker/Trade
    public long price;          // 价格 (放大 1e8)
    public long quantity;       // 数量 (放大 1e8)
    
    // 针对 Depth (预分配前 20 档，足够覆盖 depth5/depth10/depth20)
    // 注意：数组对象本身是在 Disruptor 启动时创建一次，之后只改里面的值
    public static final int MAX_DEPTH = 20;
    public final long[] bidPrices = new long[MAX_DEPTH];
    public final long[] bidQtys   = new long[MAX_DEPTH];
    public final long[] askPrices = new long[MAX_DEPTH];
    public final long[] askQtys   = new long[MAX_DEPTH];
    public int depthCount;      // 实际有效的深度档位数量

    // === 交易数据 (Order/Execution Payload) ===
    public long localOrderId;   // 内部唯一 ID
    public long clientOidHash;  // 外部 ClientID 的哈希值 (用于快速查找)
    // 也可以用 byte[] 存原始字符串，如果需要的话
    // public final byte[] clientOidRaw = new byte[32]; 
    
    public byte side;           // 1=Buy, 2=Sell
    public byte orderType;      // 1=Limit, 2=Market, 3=PostOnly
    public byte orderStatus;    // New, Filled, Canceled, Rejected
    
    public long filledQty;      // 成交数量
    public long filledPrice;    // 成交价格

    // === 辅助方法 ===

    /**
     * 在 Producer (L1) 写入前必须调用，防止脏数据污染
     */
    public void reset() {
        // 元数据重置
        type = CoreEventType.NONE;
        timestamp = 0;
        recvTime = 0;
        sequence = 0;
        
        // 路由重置
        exchangeId = 0;
        symbolId = 0;
        accountId = 0;
        
        // 这里的技巧是：不需要把数组里的每个元素都归零 (太慢)
        // 只需要把计数器归零，消费者自然不会读后面的脏数据
        depthCount = 0;
        price = 0;
        quantity = 0;
        
        // 交易数据重置
        localOrderId = 0;
        clientOidHash = 0;
        side = 0;
        orderType = 0;
        orderStatus = 0;
        filledQty = 0;
        filledPrice = 0;
    }
    
    /**
     * 辅助拷贝深度数据 (Zero Allocation)
     * L1 调用此方法把解析好的数据填进去
     */
    public void setBids(long[] prices, long[] qtys, int count) {
        int len = Math.min(count, MAX_DEPTH);
        System.arraycopy(prices, 0, this.bidPrices, 0, len);
        System.arraycopy(qtys, 0, this.bidQtys, 0, len);
        this.depthCount = len;
    }
    
    /**
     * 辅助拷贝卖盘深度数据 (Zero Allocation)
     */
    public void setAsks(long[] prices, long[] qtys, int count) {
        int len = Math.min(count, MAX_DEPTH);
        System.arraycopy(prices, 0, this.askPrices, 0, len);
        System.arraycopy(qtys, 0, this.askQtys, 0, len);
        this.depthCount = len;
    }
}
