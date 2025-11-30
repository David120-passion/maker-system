package com.xinyue.maker.io;

import com.xinyue.maker.common.Exchange;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;

/**
 * Binance 行情 WebSocket 客户端处理器。
 */
final class BinanceWebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

    private static final String SUBSCRIBE_PAYLOAD = """
            {
              "method": "SUBSCRIBE",
              "params": [
                "btcusdt@aggTrade",
                "btcusdt@depth"
              ],
              "id": 1
            }
            """;

    private final WebSocketClientHandshaker handshaker;
    private final Normalizer normalizer;
    private final Exchange exchange;
    private ChannelPromise handshakeFuture;

    BinanceWebSocketClientHandler(WebSocketClientHandshaker handshaker, Normalizer normalizer, Exchange exchange) {
        this.handshaker = handshaker;
        this.normalizer = normalizer;
        this.exchange = exchange;
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
            handshakeFuture.setFailure(new IllegalStateException("WebSocket 连接已关闭"));
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                handshakeFuture.setSuccess();
                ch.writeAndFlush(new TextWebSocketFrame(SUBSCRIBE_PAYLOAD));
            } catch (WebSocketHandshakeException e) {
                handshakeFuture.setFailure(e);
            }
            return;
        }

        if (msg instanceof FullHttpResponse response) {
            throw new IllegalStateException(
                    "意外的 FullHttpResponse: " + response.status() + ", body=" + response.content().toString(CharsetUtil.UTF_8)
            );
        }

        WebSocketFrame frame = (WebSocketFrame) msg;
        if (frame instanceof TextWebSocketFrame textFrame) {
            System.out.println(((TextWebSocketFrame) frame).text());
            byte[] payload = textFrame.text().getBytes(StandardCharsets.UTF_8);
            normalizer.onJsonMessage(exchange, payload);
        } else if (frame instanceof CloseWebSocketFrame) {
            ch.close();
        }
    }
}

