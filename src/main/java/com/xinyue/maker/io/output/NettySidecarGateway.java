package com.xinyue.maker.io.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinyue.maker.common.AssetRegistry;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.OrderCommand;
import com.xinyue.maker.common.TransferCommand;
import com.xinyue.maker.core.gateway.ExecutionGateway;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.agrona.collections.Int2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 实现类：负责将 L2 的指令翻译成 Node.js 能懂的 JSON，并塞入 Netty 管道
 */
public class NettySidecarGateway implements ExecutionGateway {
    
    private static final Logger LOG = LoggerFactory.getLogger(NettySidecarGateway.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    // 1. 物理连接：只有一条，连接到 Node.js Sidecar
    private final Channel sidecarChannel;
    
    // 2. 逻辑会话池：管理多个账号的限频和状态
    private final Int2ObjectHashMap<TradeSession> sessionPool;
    
    // 3. 交易所类型（用于初始化 TradeSession）
    private final Exchange exchange;

    public NettySidecarGateway(Channel sidecarChannel, Int2ObjectHashMap<TradeSession> sessionPool, Exchange exchange) {
        this.sidecarChannel = sidecarChannel;
        this.sessionPool = sessionPool;
        this.exchange = exchange;
    }

    /**
     * 初始化 TradeSession。
     * 根据账户信息创建并注册 TradeSession 到会话池。
     *
     * @param accountId 账户 ID
     * @param accountName 账户名称（用于日志）
     * @param mnemonicPhrase 助记词
     */
    public void initializeSession(int accountId, String accountName, String mnemonicPhrase) {
        TradeSession session = new TradeSession(accountId, accountName, exchange, mnemonicPhrase);
        sessionPool.put(accountId, session);
        LOG.info("TradeSession initialized: accountId={}, accountName={}, exchange={}", 
                accountId, accountName, exchange);
    }

    @Override
    public void sendOrder(OrderCommand cmd) {
        // --- 步骤 A: 逻辑路由与检查 (在 L2 线程执行，极快) ---
        
        // 1. 查找账号 Session
        TradeSession session = sessionPool.get(cmd.accountId);
        if (session == null) {
            LOG.error("Account {} not found, order rejected.", cmd.accountId);
            return;
        }

        // 2. 账号级限频检查 (Rate Limit)
        // 假设 RateLimiter 是非阻塞的 (如 Guava 或自研计数器)
//        if (!session.rateLimiter.tryAcquire()) {
//            LOG.warn("Account {} rate limited.", cmd.accountId);
//            // 这里可以反馈给 L2 做流控，或者直接丢弃
//            return;
//        }

        // --- 步骤 B: 协议封装 (仍在 L2 线程，Jackson 序列化通常在微秒级) ---
        
        try {
            // 构造发给 Node.js 的 JSON Payload
            ObjectNode payload = jsonMapper.createObjectNode();

            // 取消订单：OMS 只会填充 clientId/clobPairId/orderFlags/goodTilBlockTimeSec
            if (isCancelCommand(cmd)) {
                payload.put("action", "CANCEL");
                payload.put("clientId", cmd.internalOrderId); // 透传 ID
                payload.put("accountId", cmd.accountId);
                payload.put("clobPairId", cmd.clobPairId);
                payload.put("orderFlags", cmd.orderFlags);
                payload.put("goodTilBlockTime", cmd.goodTilBlockTimeSec); // 秒
                payload.put("word", session.mnemonicPhrase);
            } else {
                // 下单
                payload.put("action", "ORDER");
                payload.put("clientId", cmd.internalOrderId); // 透传 ID
                payload.put("accountId", cmd.accountId);   // 告诉 Node 用谁的私钥
                payload.put("symbol", cmd.symbolId);
                payload.put("price", cmd.priceE8);         // Node 端需处理精度
                payload.put("qty", cmd.qtyE8);
                payload.put("side", cmd.side);
                // 从 TradeSession 获取助记词
                payload.put("word", session.mnemonicPhrase);
                payload.put("goodTilTimeInSeconds",cmd.goodTilTimeInSeconds);
            }
            // 转为字符串 (Netty TextFrame)
            String jsonStr = payload.toString();
            TextWebSocketFrame frame = new TextWebSocketFrame(jsonStr);
            // --- 步骤 C: 异步交接 (Async Handoff) ---
            // 关键点：writeAndFlush 是线程安全的，且是非阻塞的。
            // 它会将任务添加到 Netty IO 线程的队列中，L2 线程立刻返回。
            if (sidecarChannel.isWritable()) {
                sidecarChannel.writeAndFlush(frame); 
                // 可选：添加监听器处理网络层面的发送失败（不在 L2 线程回调）
            } else {
                LOG.error("Sidecar channel is full/busy, dropping order {}", cmd.internalOrderId);
            }

        } catch (Exception e) {
            LOG.error("Failed to encode order", e);
        }
    }

    /**
     * 判断是否为撤单命令。
     * 约定：撤单命令不会携带 price/qty/side，但会携带 clobPairId/goodTilBlockTimeSec/orderFlags。
     */
    private static boolean isCancelCommand(OrderCommand cmd) {
        return cmd.clobPairId != 0
                && cmd.goodTilBlockTimeSec != 0L
                && cmd.priceE8 == 0L
                && cmd.qtyE8 == 0L
                && cmd.side == 0;
    }

    @Override
    public void transfer(TransferCommand cmd) {
        TradeSession fromSession = sessionPool.get(cmd.fromAccountId);
        TradeSession toSession = sessionPool.get(cmd.toAccountId);

        if (fromSession == null || toSession == null) {
            LOG.error("Transfer failed: sender or recipient account not found. From: {}, To: {}", 
                    cmd.fromAccountId, cmd.toAccountId);
            return;
        }

        try {
            ObjectNode payload = jsonMapper.createObjectNode();
            payload.put("action", "TRANSFER");
            payload.put("fromAccountId", cmd.fromAccountId);
            payload.put("toAccountId", cmd.toAccountId);
            payload.put("symbol", AssetRegistry.getInstance().getAsset(cmd.symbolId)); // 转换 assetId 回 symbol 字符串
            payload.put("qty", cmd.qtyE8);
            payload.put("exchangeId", cmd.exchangeId);
            payload.put("fromWord", fromSession.mnemonicPhrase); // 发送方助记词
            payload.put("toWord", toSession.mnemonicPhrase);     // 接收方助记词（dYdX 转账可能需要，但可能不严格需要）

            String jsonStr = payload.toString();
            TextWebSocketFrame frame = new TextWebSocketFrame(jsonStr);

            if (sidecarChannel.isWritable()) {
                sidecarChannel.writeAndFlush(frame);
                LOG.info("Transfer command sent: fromAccount={}, toAccount={}, asset={}, qty={}",
                        cmd.fromAccountId, cmd.toAccountId, 
                        AssetRegistry.getInstance().getAsset(cmd.symbolId), 
                        cmd.qtyE8 / 1_0000_0000.0);
            } else {
                LOG.error("Sidecar channel is full/busy, dropping transfer command for asset {}", 
                        AssetRegistry.getInstance().getAsset(cmd.symbolId));
            }
        } catch (Exception e) {
            LOG.error("Failed to encode transfer command", e);
        }
    }
}