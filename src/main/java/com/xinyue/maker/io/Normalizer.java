package com.xinyue.maker.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.RingBuffer;
import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.CoreEventType;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.ScaleConstants;
import com.xinyue.maker.common.SymbolRegistry;
import com.xinyue.maker.io.rest.DydxRestClient;
import com.xinyue.maker.io.input.dydx.DydxMarketDataConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;

/**
 * 将交易所原始 JSON 转换成可复用的 CoreEvent。
 * <p>
 * 设计要点：
 * 1. ObjectMapper 复用（避免每次创建）
 * 2. 直接解析 byte[]，减少 String 对象分配
 * 3. 价格/数量转换为 long（放大 1e8）
 */
public final class Normalizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RingBuffer<CoreEvent> ringBuffer;
    private final SymbolRegistry symbolRegistry;
    private final AssetRegistry assetRegistry;

    private static final Logger LOG = LoggerFactory.getLogger(Normalizer.class);

    public Normalizer(RingBuffer<CoreEvent> ringBuffer) {
        this.ringBuffer = ringBuffer;
        this.symbolRegistry = SymbolRegistry.getInstance();
        this.assetRegistry = AssetRegistry.getInstance();
    }

    public void onJsonMessage(Exchange exchange, byte[] payload) {
        long seq = ringBuffer.next();
        try {
            CoreEvent event = ringBuffer.get(seq);
            event.reset(); // 确保干净状态
            event.exchangeId = exchange.id();
            event.recvTime = System.nanoTime(); // 接收时间（纳秒精度）
            switch (exchange){
                case BINANCE ->  parseBinanceMessage(exchange, payload, event);
                case DYDX -> parseDydxMessage(exchange, payload, event);
            }
        } catch (Exception e) {
            // 解析失败时设置事件类型为 NONE，消费者会忽略
            // 注意：一旦获取了 seq，必须发布，否则会导致 RingBuffer 阻塞
            CoreEvent event = ringBuffer.get(seq);
            event.type = CoreEventType.NONE;
            // 在实际生产环境中应该记录错误日志到异步日志系统
            System.err.println("解析消息失败: " + e.getMessage());
        } finally {
            ringBuffer.publish(seq);
        }
    }

    /**
     * 解析 dYdX WebSocket 消息。
     * <p>
     * 支持的消息类型：
     * - subscribed: 订阅确认（包含全量订单簿）
     * - channel_data: 增量订单簿更新
     *
     * @param exchange 交易所
     * @param payload JSON 消息字节数组
     * @param event 当前事件对象
     */
    private void parseDydxMessage(Exchange exchange, byte[] payload, CoreEvent event) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(payload);
        String type = root.path("type").asText();
        String channel = root.path("channel").asText();
        
        if ("v4_orderbook".equals(channel)) {
            // 订单簿频道
            if ("subscribed".equals(type)) {
                // 订阅确认消息，包含全量订单簿数据
                parseDydxSnapshot(root, event);
            } else if ("channel_data".equals(type)) {
                // 增量更新消息
                parseDydxIncrementalUpdate(root, event);
            }
            LOG.debug("订单簿变动消息:{}",root.toString());

        } else if ("v4_subaccounts".equals(channel)) {
            // 账户订单频道
//            LOG.info("订单簿成交");
            parseDydxAccountOrders(root, event);
        }
    }

    /**
     * 解析 dYdX 全量订单簿快照（subscribed 消息）。
     * <p>
     * 示例：
     * {
     *   "type":"subscribed",
     *   "connection_id":"...",
     *   "message_id":1,
     *   "channel":"v4_orderbook",
     *   "id":"BTC-USDT",
     *   "contents":{
     *     "bids":[{"price":"93057","size":"0.2978"},...],
     *     "asks":[{"price":"93058","size":"0.625"},...]
     *   }
     * }
     */
    private void parseDydxSnapshot(JsonNode root, CoreEvent event) {
        event.type = CoreEventType.DEPTH_UPDATE;
        
        // 解析 symbol
        String symbol = root.path("id").asText();
        if (symbol != null && !symbol.isEmpty()) {
            // dYdX 使用 BTC-USDT 格式，转换为 BTCUSDT
            symbol = symbol.replace("-", "");
            event.symbolId = symbolRegistry.get(symbol);
        }
        
        // 时间戳（dYdX 可能没有时间戳字段，使用接收时间）
        event.timestamp = System.currentTimeMillis();
        // 使用 firstUpdateId = -1 标记这是全量快照（subscribed 消息），用于重建期间识别
        event.firstUpdateId = -1;

        // 使用 sequence 字段存储 dYdX 的 message_id，供 gap 检测使用
        long messageId = root.path("message_id").asLong(0L);
        event.sequence = messageId;
        
        // 解析订单簿内容
        JsonNode contents = root.path("contents");
        JsonNode bids = contents.path("bids");
        JsonNode asks = contents.path("asks");
        
        int bidCount = Math.min(bids.size(), CoreEvent.MAX_DEPTH);
        int askCount = Math.min(asks.size(), CoreEvent.MAX_DEPTH);
        
        // 填充买盘
        for (int i = 0; i < bidCount; i++) {
            JsonNode level = bids.get(i);
            event.bidPrices[i] = parseDecimal(level.path("price").asText());
            event.bidQtys[i] = parseDecimal(level.path("size").asText());
        }
        
        // 填充卖盘
        for (int i = 0; i < askCount; i++) {
            JsonNode level = asks.get(i);
            event.askPrices[i] = parseDecimal(level.path("price").asText());
            event.askQtys[i] = parseDecimal(level.path("size").asText());
        }
        
        event.depthCount = Math.max(bidCount, askCount);
    }

    /**
     * 解析 dYdX 增量订单簿更新（channel_data 消息）。
     * <p>
     * 示例：
     * {
     *   "type":"channel_data",
     *   "connection_id":"...",
     *   "message_id":2,
     *   "id":"BTC-USDT",
     *   "channel":"v4_orderbook",
     *   "version":"1.0.0",
     *   "contents":{
     *     "bids":[["92883","0"]],
     *     "asks":[["92884","0.5"]]
     *   }
     * }
     */
    private void parseDydxIncrementalUpdate(JsonNode root, CoreEvent event) {
        event.type = CoreEventType.DEPTH_UPDATE;
//        LOG.info(root.toString());
        // 解析 symbol
        String symbol = root.path("id").asText();
        if (symbol != null && !symbol.isEmpty()) {
            symbol = symbol.replace("-", "");
            event.symbolId = symbolRegistry.get(symbol);
        }
        
        // 时间戳
        event.timestamp = System.currentTimeMillis();
        event.firstUpdateId = 0;

        // 使用 sequence 字段存储 dYdX 的 message_id，供 gap 检测使用
        long messageId = root.path("message_id").asLong(0L);
        event.sequence = messageId;
        
        // 解析订单簿内容
        JsonNode contents = root.path("contents");
        JsonNode bids = contents.path("bids");
        JsonNode asks = contents.path("asks");
        
        int bidCount = Math.min(bids.size(), CoreEvent.MAX_DEPTH);
        int askCount = Math.min(asks.size(), CoreEvent.MAX_DEPTH);
        
        // 填充买盘（dYdX 增量格式是数组 [price, size]）
        for (int i = 0; i < bidCount; i++) {
            JsonNode level = bids.get(i);
            event.bidPrices[i] = parseDecimal(level.get(0).asText());
            event.bidQtys[i] = parseDecimal(level.get(1).asText());
        }
        
        // 填充卖盘
        for (int i = 0; i < askCount; i++) {
            JsonNode level = asks.get(i);
            event.askPrices[i] = parseDecimal(level.get(0).asText());
            event.askQtys[i] = parseDecimal(level.get(1).asText());
        }
        event.depthCount = Math.max(bidCount, askCount);
    }

    /**
     * 解析 Binance WebSocket 消息。
     * 支持的消息类型：
     * - aggTrade: 归集交易
     * - depth: 深度更新（待实现）
     */
    private void parseBinanceMessage(Exchange exchange, byte[] payload, CoreEvent event) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(payload);

        // 检查是否是订阅响应（忽略）
        if (root.has("result") || root.has("id")) {
            return; // 跳过订阅确认消息
        }

        String eventType = root.path("e").asText();
        if ("aggTrade".equals(eventType)) {
            parseAggTrade(root, event);
        } else if ("depthUpdate".equals(eventType)) {
            // 解析 depthUpdate 并填充到 CoreEvent，通过 Disruptor 推送到核心层处理
            parseDepthUpdate(root, event);
        }
    }

    /**
     * 解析 aggTrade 消息。
     * <p>
     * 示例消息：
     * {
     *   "e": "aggTrade",
     *   "E": 1672515782136,
     *   "s": "BNBBTC",
     *   "a": 12345,
     *   "p": "0.001",
     *   "q": "100",
     *   "f": 100,
     *   "l": 105,
     *   "T": 1672515782136,
     *   "m": true
     * }
     */
    private void parseAggTrade(JsonNode root, CoreEvent event) {
        // 设置事件类型
        event.type = CoreEventType.MARKET_DATA_TICK;

        // 解析 symbol 并映射到 symbolId
        String symbol = root.path("s").asText();
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("aggTrade 消息缺少 symbol 字段");
        }
        event.symbolId = symbolRegistry.get(symbol);

        // 时间戳（交易所时间，毫秒）
        event.timestamp = root.path("T").asLong();
        if (event.timestamp == 0) {
            event.timestamp = root.path("E").asLong(); // 备用字段
        }

        // 序列号（使用归集交易ID）
        event.sequence = root.path("a").asLong();

        // 价格和数量（字符串转 long，放大 1e8）
        String priceStr = root.path("p").asText();
        String qtyStr = root.path("q").asText();

        if (priceStr != null && !priceStr.isEmpty()) {
            event.price = parseDecimal(priceStr);
        }
        if (qtyStr != null && !qtyStr.isEmpty()) {
            event.quantity = parseDecimal(qtyStr);
        }

        // accountId 保持为 0（公共行情）
        event.accountId = 0;
    }

    /**
     * 解析深度更新消息。
     * <p>
     * 示例消息：
     * {
     *   "e": "depthUpdate",
     *   "E": 1672515782136,
     *   "s": "BTCUSDT",
     *   "U": 82233761282,
     *   "u": 82233762460,
     *   "b": [["86818.34", "0.17882"], ...],
     *   "a": [["86818.35", "0.625"], ...]
     * }
     */
    private void parseDepthUpdate(JsonNode root, CoreEvent event) {
        event.type = CoreEventType.DEPTH_UPDATE;
        
        // 解析 symbol
        String symbol = root.path("s").asText();
        if (symbol != null && !symbol.isEmpty()) {
            event.symbolId = symbolRegistry.get(symbol);
        }
        
        // 时间戳
        event.timestamp = root.path("E").asLong();
        
        // U 和 u (firstUpdateId 和 lastUpdateId)
        event.firstUpdateId = root.path("U").asLong();
        event.sequence = root.path("u").asLong(); // 使用 sequence 字段存储 u
        
        // 解析 bids 和 asks
        JsonNode bids = root.path("b");
        JsonNode asks = root.path("a");
        
        int bidCount = Math.min(bids.size(), CoreEvent.MAX_DEPTH);
        int askCount = Math.min(asks.size(), CoreEvent.MAX_DEPTH);
        
        // 填充买盘
        for (int i = 0; i < bidCount; i++) {
            JsonNode level = bids.get(i);
            event.bidPrices[i] = parseDecimal(level.get(0).asText());
            event.bidQtys[i] = parseDecimal(level.get(1).asText());
        }
        
        // 填充卖盘
        for (int i = 0; i < askCount; i++) {
            JsonNode level = asks.get(i);
            event.askPrices[i] = parseDecimal(level.get(0).asText());
            event.askQtys[i] = parseDecimal(level.get(1).asText());
        }
        
        // 设置深度档位数量（取买盘和卖盘的最大值，实际使用时会分别处理）
        event.depthCount = Math.max(bidCount, askCount);
    }

    /**
     * 将十进制字符串转换为 long（放大 1e8）。
     * <p>
     * 示例："0.001" -> 10000000L
     * 示例："100.5" -> 10050000000L
     * <p>
     * 注意：此方法针对 Binance 格式优化，假设输入格式规范。
     * 对于超过8位小数的数字，会截断到8位（不四舍五入，避免舍入误差）。
     */
    private long parseDecimal(String decimalStr) {
        if (decimalStr == null || decimalStr.isEmpty()) {
            return 0;
        }

        int dotIndex = decimalStr.indexOf('.');
        if (dotIndex == -1) {
            // 整数
            long integerPart = Long.parseLong(decimalStr);
            return integerPart * ScaleConstants.SCALE_E8;
        }

        // 小数部分
        String integerPartStr = decimalStr.substring(0, dotIndex);
        String fractionalPartStr = decimalStr.substring(dotIndex + 1);

        long integerPart = integerPartStr.isEmpty() ? 0 : Long.parseLong(integerPartStr);
        
        if (fractionalPartStr.isEmpty()) {
            return integerPart * ScaleConstants.SCALE_E8;
        }

        // 计算小数位数并处理超过8位的情况
        int fractionalDigits = fractionalPartStr.length();
        long fractionalPart;
        
        if (fractionalDigits > 8) {
            // 截断到8位（避免使用Math.pow，直接字符串截断更高效）
            fractionalPartStr = fractionalPartStr.substring(0, 8);
            fractionalPart = Long.parseLong(fractionalPartStr);
            fractionalDigits = 8;
        } else {
            fractionalPart = Long.parseLong(fractionalPartStr);
        }

        // 放大到 1e8（使用查表法避免Math.pow的开销）
        long scaleMultiplier = SCALE_MULTIPLIERS[8 - fractionalDigits];
        return integerPart * ScaleConstants.SCALE_E8 + fractionalPart * scaleMultiplier;
    }

    /**
     * 解析 dYdX 账户订单消息（v4_subaccounts 频道）。
     * <p>
     * 支持的消息类型：
     * - subscribed: 订阅确认（包含现有订单，message_id == 1）
     * - channel_data: 订单更新（新订单、取消订单、部分成交）
     * <p>
     * 参考 parseDydxSnapshot 的实现方式，同步模式下将所有订单填充到一个事件的数组中。
     *
     * @param root JSON 根节点
     * @param event 当前事件对象
     */
    private void parseDydxAccountOrders(JsonNode root, CoreEvent event) {
        String id = root.path("id").asText(); // address/subaccountNumber
        
        // 使用 sequence 字段存储 message_id
        long messageId = root.path("message_id").asLong(0L);
        
        // 判断是否是同步订单（message_id == 1 表示订阅确认，需要同步所有订单）
        boolean isSync = (messageId == 1);
        
        // 解析订单数据
        JsonNode contents = root.path("contents");
        
        // 解析订单列表（orders）
        if (contents.has("orders")) {


            JsonNode orders = contents.path("orders");
            if (orders.isArray() && orders.size() > 0) {
                if (isSync) {
                    // 同步模式：将所有订单填充到一个 CoreEvent 的数组中（参考 parseDydxSnapshot）
                    event.type = CoreEventType.ACCOUNT_ORDER_UPDATE;
                    event.firstUpdateId = -1; // 标记为同步
                    event.sequence = messageId;
                    event.timestamp = System.currentTimeMillis();

                    String address = null;
                    // 解析账户信息
                    if (id != null && id.contains("/")) {
                        // TODO: 根据 address 映射到 accountId
                         address = id.split("/")[0];
                         if(address.equals("ba1pqhys5muk0tpftmvpak87rakycs9vh3rp6rgv8")) {
                             event.accountId = 1;
                         }
                    }

                    
                    // 遍历所有订单，填充到数组中
                    int orderCount = Math.min(orders.size(), CoreEvent.MAX_ORDERS);
                    for (int i = 0; i < orderCount; i++) {
                        JsonNode order = orders.get(i);
                        
                        // 解析 clientId
                        String clientId = order.path("clientId").asText();
                        if (clientId != null && !clientId.isEmpty()) {
                            try {
                                event.orderClientIds[i] = Long.parseLong(clientId);
                            } catch (NumberFormatException e) {
                                event.orderClientIds[i] = clientId.hashCode();
                            }
                        }
                        
                        // 解析价格和数量
                        String priceStr = order.path("price").asText();
                        String sizeStr = order.path("size").asText();
                        String totalFilledStr = order.path("totalFilled").asText();
                        
                        if (priceStr != null && !priceStr.isEmpty()) {
                            event.orderPrices[i] = parseDecimal(priceStr);
                        }
                        if (sizeStr != null && !sizeStr.isEmpty()) {
                            event.orderQtys[i] = parseDecimal(sizeStr);
                        }
                        if (totalFilledStr != null && !totalFilledStr.isEmpty()) {
                            event.orderFilledQtys[i] = parseDecimal(totalFilledStr);
                        }
                        
                        // 解析订单方向
                        String side = order.path("side").asText();
                        if ("BUY".equals(side)) {
                            event.orderSides[i] = 0; // Buy
                        } else if ("SELL".equals(side)) {
                            event.orderSides[i] = 1; // Sell
                        }
                        
                        // 解析订单状态
                        String status = order.path("status").asText();
                        if ("OPEN".equals(status)) {
                            event.orderStatuses[i] = 3; // New
                        } else if ("CANCELED".equals(status)) {
                            event.orderStatuses[i] = 6; // Canceled
                        } else if ("FILLED".equals(status)) {
                            event.orderStatuses[i] = 5; // Filled
                        }
                        
                        // 解析交易对
                        String ticker = order.path("ticker").asText();
                        if (ticker != null && !ticker.isEmpty()) {
                            String symbol = ticker.replace("-", "");
                            event.orderSymbolIds[i] = symbolRegistry.get(symbol);
                        }

                        // === dYdX v4 cancel 需要的字段 ===
                        // clobPairId
                        String clobPairIdStr = order.path("clobPairId").asText();
                        if (clobPairIdStr != null && !clobPairIdStr.isEmpty()) {
                            try {
                                event.orderClobPairIds[i] = Integer.parseInt(clobPairIdStr);
                            } catch (NumberFormatException ignore) {
                                event.orderClobPairIds[i] = 0;
                            }
                        }
                        // orderFlags
                        String flagsStr = order.path("orderFlags").asText();
                        if (flagsStr != null && !flagsStr.isEmpty()) {
                            try {
                                event.orderFlags[i] = Long.parseLong(flagsStr);
                            } catch (NumberFormatException ignore) {
                                event.orderFlags[i] = 0L;
                            }
                        }
                        // goodTilBlockTime (ISO-8601 -> epoch seconds)
                        String gtt = order.path("goodTilBlockTime").asText();
                        if (gtt != null && !gtt.isEmpty()) {
                            event.orderGoodTilBlockTimeSec[i] = parseIsoToEpochSeconds(gtt);
                        }
                    }
                    
                    // 设置订单数量
                    event.orderCount = orderCount;
                } else {
                    // 增量更新模式：只处理第一个订单
                    event.type = CoreEventType.ACCOUNT_ORDER_UPDATE;
                    event.firstUpdateId = 0; // 标记为增量更新
                    event.sequence = messageId;
                    event.timestamp = System.currentTimeMillis();
                    
                    // 解析账户信息
                    String address = null;
                    if (id != null && id.contains("/")) {
                        // TODO: 根据 address 映射到 accountId
                        address = id.split("/")[0];
                        if(address.equals("ba1pqhys5muk0tpftmvpak87rakycs9vh3rp6rgv8")) {
                            event.accountId = 1;
                        }
                    }
                    
                    JsonNode firstOrder = orders.get(0);
                    parseDydxOrder(firstOrder, event, isSync);
                    
                    // 解析成交列表（fills）- 部分成交/多笔成交时会推送，可能包含多条成交
                    if (contents.has("fills")) {
                        JsonNode fills = contents.path("fills");
                        if (fills.isArray() && fills.size() > 0) {
                            int max = Math.min(fills.size(), CoreEvent.MAX_FILLS);
                            long totalQtyE8 = 0L;
                            long lastPriceE8 = 0L;

                            for (int i = 0; i < max; i++) {
                                JsonNode fill = fills.get(i);

                                String priceStr = fill.path("price").asText();
                                String sizeStr = fill.path("size").asText();

                                if (priceStr != null && !priceStr.isEmpty()) {
                                    long p = parseDecimal(priceStr);
                                    event.fillPrices[i] = p;
                                    lastPriceE8 = p;
                                }
                                if (sizeStr != null && !sizeStr.isEmpty()) {
                                    long q = parseDecimal(sizeStr);
                                    event.fillQtys[i] = q;
                                    totalQtyE8 += q;
                                }
                            }

                            event.fillCount = max;

                            // 兼容：同时在 price/quantity 上提供聚合信息，供简单策略使用
                            if (lastPriceE8 > 0) {
                                event.price = lastPriceE8;
                            }
                            if (totalQtyE8 > 0) {
                                event.totalFillQty = totalQtyE8;
                            }
                        }
                    }
                }
            }
        }
        
        // 解析余额数据（assetPositions）- 每次余额变动都会推送
        if (contents.has("assetPositions")) {
            JsonNode assetPositions = contents.path("assetPositions");
            if (assetPositions.isArray() && assetPositions.size() > 0) {
                // 设置事件类型为账户订单更新（余额更新也通过这个事件类型处理）
                if (event.type == CoreEventType.NONE) {
                    event.type = CoreEventType.ACCOUNT_ORDER_UPDATE;
                    event.sequence = messageId;
                    event.timestamp = System.currentTimeMillis();
                }
                
                // 解析账户信息
                String address = null;
                if (id != null && id.contains("/")) {
                    // TODO: 根据 address 映射到 accountId
                    address = id.split("/")[0];
                    if(address.equals("ba1pqhys5muk0tpftmvpak87rakycs9vh3rp6rgv8")) {
                        event.accountId = 1;
                    }
                }
                
                // 遍历所有资产，填充到数组中
                int assetCount = Math.min(assetPositions.size(), CoreEvent.MAX_ASSETS);
                for (int i = 0; i < assetCount; i++) {
                    JsonNode assetPosition = assetPositions.get(i);
                    
                    // 解析资产符号
                    String symbol = assetPosition.path("symbol").asText();
                    if (symbol != null && !symbol.isEmpty()) {
                        // 将资产符号转换为 assetId
                        short assetId = assetRegistry.get(symbol);
                        if (assetId > 0) {
                            event.assetIds[i] = assetId;
                        }
                    }
                    
                    // 解析余额（size）
                    String sizeStr = assetPosition.path("size").asText();
                    if (sizeStr != null && !sizeStr.isEmpty()) {
                        // 使用 DydxRestClient 的解析方法将字符串转换为 long（放大 1e8）
                        long sizeE8 = DydxRestClient.parseSizeToLong(sizeStr);
                        event.assetBalances[i] = sizeE8;
                    }
                }
                
                // 设置资产数量
                event.assetCount = assetCount;
            }
        }
        
        // 解析转账数据（transfers）- 转账时会推送
        if (contents.has("transfers")) {
            JsonNode transfers = contents.path("transfers");
            if (transfers.isObject() && !transfers.isEmpty()) {
                // 设置事件类型为账户订单更新（转账也通过这个事件类型处理）
                if (event.type == CoreEventType.NONE) {
                    event.type = CoreEventType.ACCOUNT_ORDER_UPDATE;
                    event.sequence = messageId;
                    event.timestamp = System.currentTimeMillis();
                }
                
                // 解析账户信息
                if (id != null && id.contains("/")) {
                    // TODO: 根据 address 映射到 accountId
                    // String address = id.split("/")[0];
                    // event.accountId = getAccountIdByAddress(address);
                }
                
                // 解析转账信息
                String symbol = transfers.path("symbol").asText();
                String sizeStr = transfers.path("size").asText();
                String typeStr = transfers.path("type").asText();
                
                // 解析接收方地址，判断是否是转入（TRANSFER_IN）还是转出（TRANSFER_OUT）
                JsonNode recipient = transfers.path("recipient");
                String recipientAddress = recipient.path("address").asText();
                
                // 判断转账方向：如果接收方是当前账户，则是转入；否则是转出
                boolean isTransferIn = false;
                if (id != null && id.contains("/")) {
                    String currentAddress = id.split("/")[0];
                    if (currentAddress.equals(recipientAddress)) {
                        isTransferIn = true;
                    }
                } else if ("TRANSFER_IN".equals(typeStr)) {
                    // 如果 type 字段明确标识为 TRANSFER_IN，则认为是转入
                    isTransferIn = true;
                }
                
                // 解析资产符号并转换为 assetId
                if (symbol != null && !symbol.isEmpty()) {
                    short assetId = assetRegistry.get(symbol);
                    if (assetId > 0) {
                        event.transferAssetId = assetId;
                    }
                }
                
                // 解析转账数量
                if (sizeStr != null && !sizeStr.isEmpty()) {
                    long sizeE8 = DydxRestClient.parseSizeToLong(sizeStr);
                    event.transferAmountE8 = sizeE8;
                }
                
                // 设置转账类型：0=转入，1=转出
                event.transferType = (byte) (isTransferIn ? 0 : 1);
            }
        }
    }

    /**
     * 解析单个 dYdX 订单。
     *
     * @param order 订单 JSON 节点
     * @param event CoreEvent 对象
     * @param isSync 是否是同步订单（message_id == 1）
     */
    private void parseDydxOrder(JsonNode order, CoreEvent event, boolean isSync) {
        // clientId 是客户端自定义的 ID，用于匹配订单（唯一标识）
        String clientId = order.path("clientId").asText();
        if (clientId != null && !clientId.isEmpty()) {
            // 将 clientId 转换为 long 存储在 clientOidHash 中
            try {
                event.clientOidHash = Long.parseLong(clientId);
            } catch (NumberFormatException e) {
                // 如果 clientId 不是数字，使用 hashCode
                event.clientOidHash = clientId.hashCode();
            }
        }
        
        // 注意：不使用交易所订单ID（id字段），只用 clientId 作为标识
        
        // 订单状态
        String status = order.path("status").asText();
        if ("OPEN".equals(status)) {
            event.orderStatus = 3; // New
        } else if ("CANCELED".equals(status)) {
            event.orderStatus = 6; // Canceled
        } else if ("FILLED".equals(status)) {
            event.orderStatus = 5; // Filled
        }
        
        // 订单方向
        String side = order.path("side").asText();
        if ("BUY".equals(side)) {
            event.side = 0; // Buy
        } else if ("SELL".equals(side)) {
            event.side = 1; // Sell
        }
        
        // 价格和数量
        String priceStr = order.path("price").asText();
        String sizeStr = order.path("size").asText();
        String totalFilledStr = order.path("totalFilled").asText();
        
        if (priceStr != null && !priceStr.isEmpty()) {
            event.price = parseDecimal(priceStr);
        }
        if (sizeStr != null && !sizeStr.isEmpty()) {
            event.quantity = parseDecimal(sizeStr);
        }
        if (totalFilledStr != null && !totalFilledStr.isEmpty()) {
            event.filledQty = parseDecimal(totalFilledStr);
        }
        
        // 交易对
        String ticker = order.path("ticker").asText();
        if (ticker != null && !ticker.isEmpty()) {
            String symbol = ticker.replace("-", "");
            event.symbolId = symbolRegistry.get(symbol);
        }

        // === dYdX v4 cancel 需要的字段 ===
        // clobPairId / orderFlags / goodTilBlockTime
        String clobPairIdStr = order.path("clobPairId").asText();
        if (clobPairIdStr != null && !clobPairIdStr.isEmpty()) {
            try {
                event.clobPairId = Integer.parseInt(clobPairIdStr);
            } catch (NumberFormatException ignore) {
                event.clobPairId = 0;
            }
        }

        String flagsStr = order.path("orderFlags").asText();
        if (flagsStr != null && !flagsStr.isEmpty()) {
            try {
                event.orderFlag = Long.parseLong(flagsStr);
            } catch (NumberFormatException ignore) {
                event.orderFlag = 0L;
            }
        }

        String gtt = order.path("goodTilBlockTime").asText();
        if (gtt != null && !gtt.isEmpty()) {
            event.goodTilBlockTimeSec = parseIsoToEpochSeconds(gtt);
        }
    }

    /**
     * ISO-8601 时间字符串 -> epoch seconds.
     * 仅在 L1 Normalizer 使用（非 L2 热路径）。
     */
    private long parseIsoToEpochSeconds(String iso) {
        try {
            return Instant.parse(iso).getEpochSecond();
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    // 预计算的缩放因子表（10^0 到 10^8）
    private static final long[] SCALE_MULTIPLIERS = {
        1L,           // 10^0
        10L,          // 10^1
        100L,                  // 10^2
        1_000L,                // 10^3
        10_000L,               // 10^4
        100_000L,              // 10^5
        1_000_000L,            // 10^6
        10_000_000L,           // 10^7
        ScaleConstants.SCALE_E8  // 10^8
    };
}

