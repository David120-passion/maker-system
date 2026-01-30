package com.xinyue.maker.io.input.dydx;

import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.infra.OriginalMessageDao;
import com.xinyue.maker.io.Normalizer;
import com.xinyue.maker.strategy.InternalRangeOscillatorStrategy;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.channel.ChannelPipeline;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * dYdX WebSocket 客户端处理器。
 */
final class DydxWebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger LOG = LoggerFactory.getLogger(InternalRangeOscillatorStrategy.class);
    public static int count = 0;
    private static final String SUBSCRIBE_TEMPLATE = """
            {
              "type": "subscribe",
              "channel": "v4_orderbook",
              "id": "%s"
            }
            """;

    private static final String UNSUBSCRIBE_TEMPLATE = """
            {
              "type": "unsubscribe",
              "channel": "v4_orderbook",
              "id": "%s"
            }
            """;

    private static final String SUBSCRIBE_ACCOUNT_ORDERS_TEMPLATE = """
            {
              "type": "subscribe",
              "channel": "v4_subaccounts",
              "id": "%s/%d",
              "marketType": "SPOT"
            }
            """;

    private static final String UNSUBSCRIBE_ACCOUNT_ORDERS_TEMPLATE = """
            {
              "type": "unsubscribe",
              "channel": "v4_subaccounts",
              "id": "%s/%d",
              "marketType": "SPOT"
            }
            """;

    public static final class AccountSubscription {
        public final String address;
        public final int subaccountNumber;
        
        public AccountSubscription(String address, int subaccountNumber) {
            this.address = address;
            this.subaccountNumber = subaccountNumber;
        }
    }

    private final WebSocketClientHandshaker handshaker;
    private final Normalizer normalizer;
    private final Exchange exchange;

    private final OriginalMessageDao originalMessageDao;

    // 当前订阅的 symbol，默认 BTC-USDT，后续可扩展为多币种
    private volatile String symbol = "H2-USDT";

    // 账户订单订阅配置（由上层在握手前配置，支持多个账户）
    private final List<AccountSubscription> accountSubscriptions = new ArrayList<>();
    private ChannelPromise handshakeFuture;
    
    // WebSocket 最大帧大小
    private final int maxFramePayloadLength;


    public DydxWebSocketClientHandler(WebSocketClientHandshaker handshaker, Normalizer normalizer, Exchange exchange, OriginalMessageDao originalMessageDao, List<AccountSubscription> accountSubscriptions, int maxFramePayloadLength) {
        this.handshaker = handshaker;
        this.normalizer = normalizer;
        this.exchange = exchange;
        this.originalMessageDao = originalMessageDao;
        this.maxFramePayloadLength = maxFramePayloadLength;
        if (accountSubscriptions != null) {
            this.accountSubscriptions.addAll(accountSubscriptions);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        handshakeFuture = ctx.newPromise();
    }

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!handshakeFuture.isDone()) {
            handshakeFuture.setFailure(new IllegalStateException("WebSocket collect closed"));
            LOG.info("dydx subcribe handshake collection was close");
        }
        LOG.info("dydx subcribe orderbook collection was close ");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                handshakeFuture.setSuccess();
                
                // 握手完成后，替换 pipeline 中的 WebSocket 解码器，设置正确的最大帧大小
                // handshaker.finishHandshake 会自动添加 WebSocket13FrameDecoder，但使用的是默认的 64KB
                // 我们需要替换它
                ChannelPipeline pipeline = ch.pipeline();
                // 找到 handshaker 添加的默认解码器并替换
                io.netty.channel.ChannelHandler decoder = pipeline.get(WebSocket13FrameDecoder.class);
                if (decoder != null) {
                    String decoderName = pipeline.context(decoder).name();
                    pipeline.replace(decoderName, "ws-decoder-large", new WebSocket13FrameDecoder(false, true, maxFramePayloadLength));
                }
                // 找到 handshaker 添加的默认编码器并替换（如果需要）
                io.netty.channel.ChannelHandler encoder = pipeline.get(WebSocket13FrameEncoder.class);
                if (encoder != null) {
                    String encoderName = pipeline.context(encoder).name();
                    pipeline.replace(encoderName, "ws-encoder-large", new WebSocket13FrameEncoder(true));
                }
                
////                 初始订阅订单簿
//                //todo 订单簿要单独连接 因为多个连接的情况下 messageId不是连续的 无法判断有没有丢包
//                ch.writeAndFlush(new TextWebSocketFrame(String.format(SUBSCRIBE_TEMPLATE, symbol)));
////                 如已配置账户订单订阅，则在握手成功后立即订阅 v4_subaccounts（支持多个账户）
//                for (AccountSubscription sub : accountSubscriptions) {
//                    subscribeAccountOrders(ch, sub.address, sub.subaccountNumber);
//                }
            } catch (WebSocketHandshakeException e) {
                handshakeFuture.setFailure(e);
            }
            return;
        }

        if (msg instanceof FullHttpResponse response) {
            throw new IllegalStateException(
                    "unexpected FullHttpResponse: " + response.status() + ", body=" + response.content().toString(CharsetUtil.UTF_8)
            );
        }

        WebSocketFrame frame = (WebSocketFrame) msg;
        if (frame instanceof TextWebSocketFrame textFrame) {
//            originalMessageDao.insertAsync("0",exchange.id()+"",((TextWebSocketFrame) frame).text(),"btcusdt");
//            System.out.println(((TextWebSocketFrame) frame).text());
            byte[] payload = textFrame.text().getBytes(StandardCharsets.UTF_8);
            normalizer.onJsonMessage(exchange, payload);
        } else if (frame instanceof PingWebSocketFrame) {
            // 回复 PONG
            LOG.info("heartbeat run ");
            ch.writeAndFlush(new PongWebSocketFrame(frame.content()));
        } else if (frame instanceof CloseWebSocketFrame) {
            ch.close();
        }
    }

    /**
     * 订阅订单簿（v4_orderbook 频道）。
     *
     * @param ch WebSocket 通道
     * @param symbol 交易对符号（如 "H2-USDT"）
     */
    void subscribeOrderBook(Channel ch, String symbol) {
        String subscribeMsg = String.format(SUBSCRIBE_TEMPLATE, symbol);
        LOG.info("订阅订单簿: {}", subscribeMsg);
        ch.writeAndFlush(new TextWebSocketFrame(subscribeMsg));
    }

    /**
     * 由接入层在检测到 dYdX gap 时调用，执行「取消订阅 + 重新订阅」。
     */
    void resubscribe(Channel ch, String symbol) {
        this.symbol = symbol;
        String unsub = String.format(UNSUBSCRIBE_TEMPLATE, symbol);
        String sub = String.format(SUBSCRIBE_TEMPLATE, symbol);
        ch.writeAndFlush(new TextWebSocketFrame(unsub));
        ch.writeAndFlush(new TextWebSocketFrame(sub));
    }

    /**
     * 订阅账户订单（v4_subaccounts 频道）。
     *
     * @param ch WebSocket 通道
     * @param address 账户地址
     * @param subaccountNumber 子账户编号
     */
    void subscribeAccountOrders(Channel ch, String address, int subaccountNumber) {
        String subscribeMsg = String.format(SUBSCRIBE_ACCOUNT_ORDERS_TEMPLATE, address, subaccountNumber);
        System.out.println(subscribeMsg);
        ch.writeAndFlush(new TextWebSocketFrame(subscribeMsg));
    }

    /**
     * 退订订单簿（v4_orderbook 频道）。
     *
     * @param ch WebSocket 通道
     * @param symbol 交易对符号（如 "H2-USDT"）
     */
    void unsubscribeOrderBook(Channel ch, String symbol) {
        String unsubscribeMsg = String.format(UNSUBSCRIBE_TEMPLATE, symbol);
        LOG.info("退订订单簿: {}", unsubscribeMsg);
        ch.writeAndFlush(new TextWebSocketFrame(unsubscribeMsg));
    }

    /**
     * 退订账户订单（v4_subaccounts 频道）。
     *
     * @param ch WebSocket 通道
     * @param address 账户地址
     * @param subaccountNumber 子账户编号
     */
    void unsubscribeAccountOrders(Channel ch, String address, int subaccountNumber) {
        String unsubscribeMsg = String.format(UNSUBSCRIBE_ACCOUNT_ORDERS_TEMPLATE, address, subaccountNumber);
        LOG.info("退订账户订单: {}", unsubscribeMsg);
        ch.writeAndFlush(new TextWebSocketFrame(unsubscribeMsg));
    }

    /**
     * 由上层在连接建立前配置账户订单订阅信息（支持多个账户）。
     * 在握手成功后会自动发送订阅消息；如果此时连接已就绪，则立即订阅。
     */
    void addAccountSubscription(String address, int subaccountNumber) {
        accountSubscriptions.add(new AccountSubscription(address, subaccountNumber));
        // 如果连接已就绪，立即订阅
        Channel ch = handshakeFuture.channel();
        if (ch != null && ch.isActive() && handshaker.isHandshakeComplete()) {
            subscribeAccountOrders(ch, address, subaccountNumber);
        }
    }
}
