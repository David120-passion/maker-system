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
    public long sequence;       // 消息序号 (用于丢包检测，对于 depthUpdate 存储 u)
    public long firstUpdateId; // 对于 depthUpdate 事件，存储 U (首次更新ID)
    public CoreEventType type;  // 事件类型 (枚举引用不仅也是无GC的，只要不中途创建)

    // === 路由信息 (Routing) ===
    public short exchangeId;    // 交易所 ID
    public short symbolId;      // 币种 ID
    public short accountId;     // 账户 ID (0 表示公共行情)

    // === 行情数据 (Market Data Payload) ===
    // 针对 Ticker/Trade  如果是订单簿成交信息 quantity代表一次的总成交量
    public long price;          // 价格 (放大 1e8)
    public long quantity;       // 数量 (放大 1e8)
    
    // 针对 Depth (预分配前 20 档，足够覆盖 depth5/depth10/depth20)
    // 注意：数组对象本身是在 Disruptor 启动时创建一次，之后只改里面的值
    public static final int MAX_DEPTH = 500;
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
    
    public byte side;           // 0=Buy, 1=Sell
    public byte orderType;      // 1=Limit, 2=Market, 3=PostOnly
    public byte orderStatus;    // New, Filled, Canceled, Rejected
    
    public long filledQty;      // 成交数量（单笔或聚合）
    public long filledPrice;    // 成交价格（单笔或聚合）

    // === 成交明细数据 (Per-Fill Payload) ===
    // 用于像 dYdX 这种一个消息里带多笔 fills 的场景，策略层可以逐笔分析
    public static final int MAX_FILLS = 500;      // 单条消息最多预留 16 笔成交
    public final long[] fillPrices = new long[MAX_FILLS];   // 每笔成交价（1e8 放大）
    public final long[] fillQtys   = new long[MAX_FILLS];   // 每笔成交量（1e8 放大）
    public int fillCount;                                 // 实际成交笔数
    public long totalFillQty;                             //实际成交数量


    // === 账户订单批量数据 (Account Order Batch Payload) ===
    // 用于同步账户订单（message_id == 1），参考 depthCount 的实现方式
    public static final int MAX_ORDERS = 500; // 预分配最多 100 个订单
    public final long[] orderClientIds = new long[MAX_ORDERS];  // clientId 数组
    public final long[] orderPrices = new long[MAX_ORDERS];      // 价格数组（放大 1e8）
    public final long[] orderQtys = new long[MAX_ORDERS];        // 数量数组（放大 1e8）
    public final long[] orderFilledQtys = new long[MAX_ORDERS]; // 已成交数量数组（放大 1e8）
    public final byte[] orderSides = new byte[MAX_ORDERS];      // 方向数组（1=Buy, 2=Sell）
    public final byte[] orderStatuses = new byte[MAX_ORDERS];   // 状态数组
    public final short[] orderSymbolIds = new short[MAX_ORDERS]; // 交易对ID数组
    // dYdX v4 cancel 需要的字段：clobPairId / orderFlags / goodTilBlockTime
    public final int[] orderClobPairIds = new int[MAX_ORDERS];    // clobPairId（原始整型）
    public final long[] orderFlags = new long[MAX_ORDERS];        // orderFlags
    public final long[] orderGoodTilBlockTimeSec = new long[MAX_ORDERS]; // goodTilBlockTime（epoch seconds）
    public int orderCount;      // 实际订单数量

    // === 账户订单增量字段（单笔） ===
    // 仅用于增量更新（message_id != 1），避免策略/OMS 再去翻数组
    public int clobPairId;
    public long orderFlag;
    public long goodTilBlockTimeSec;

    // === 账户余额数据 (Account Balance Payload) ===
    // 用于同步账户余额（assetPositions），参考 orderCount 的实现方式
    public static final int MAX_ASSETS = 100; // 预分配最多 100 个资产
    public final short[] assetIds = new short[MAX_ASSETS];      // 资产ID数组（assetId，如 USDT、BTC）
    public final long[] assetBalances = new long[MAX_ASSETS];   // 余额数组（放大 1e8）
    public int assetCount;      // 实际资产数量

    // === 转账数据 (Transfer Payload) ===
    // 用于处理账户转账（transfers）
    public short transferAssetId;    // 转账资产ID（assetId，如 USDT、BTC）
    public long transferAmountE8;   // 转账数量（放大 1e8）
    public byte transferType;       // 转账类型：0=TRANSFER_IN（转入），1=TRANSFER_OUT（转出）

    // === 辅助方法 ===

    /**
     * 在 Producer (L1) 写入前必须调用，防止脏数据污染
     * <p>
     * 注意：为了安全起见，需要重置所有数组，因为下游代码可能会通过检查非零值来确定有效数据范围
     * （例如 CoreEventHandler 通过检查 bidPrices[i] != 0 来确定 bidCount）
     */
    public void reset() {
        // 元数据重置
        type = CoreEventType.NONE;
        timestamp = 0;
        recvTime = 0;
        sequence = 0;
        firstUpdateId = 0;
        
        // 路由重置
        exchangeId = 0;
        symbolId = 0;
        accountId = 0;
        
        // 行情数据重置
        depthCount = 0;
        price = 0;
        quantity = 0;
        
        // 订单簿数组重置（必须重置，因为下游通过检查非零值确定有效范围）
        java.util.Arrays.fill(bidPrices, 0);
        java.util.Arrays.fill(bidQtys, 0);
        java.util.Arrays.fill(askPrices, 0);
        java.util.Arrays.fill(askQtys, 0);
        
        // 交易数据重置
        localOrderId = 0;
        clientOidHash = 0;
        side = 0;
        orderType = 0;
        orderStatus = 0;
        filledQty = 0;
        filledPrice = 0;

        // 成交明细重置
        fillCount = 0;
        totalFillQty=0;
        java.util.Arrays.fill(fillPrices, 0);
        java.util.Arrays.fill(fillQtys, 0);
        
        // 账户订单批量数据重置
        orderCount = 0;
        java.util.Arrays.fill(orderClientIds, 0);
        java.util.Arrays.fill(orderPrices, 0);
        java.util.Arrays.fill(orderQtys, 0);
        java.util.Arrays.fill(orderFilledQtys, 0);
        java.util.Arrays.fill(orderSides, (byte) 0);
        java.util.Arrays.fill(orderStatuses, (byte) 0);
        java.util.Arrays.fill(orderSymbolIds, (short) 0);
        java.util.Arrays.fill(orderClobPairIds, 0);
        java.util.Arrays.fill(orderFlags, 0);
        java.util.Arrays.fill(orderGoodTilBlockTimeSec, 0);
        
        // 单笔增量字段重置
        clobPairId = 0;
        orderFlag = 0;
        goodTilBlockTimeSec = 0;
        
        // 账户余额数据重置
        assetCount = 0;
        java.util.Arrays.fill(assetIds, (short) 0);
        java.util.Arrays.fill(assetBalances, 0);
        
        // 转账数据重置
        transferAssetId = 0;
        transferAmountE8 = 0;
        transferType = 0;
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
