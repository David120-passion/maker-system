package com.xinyue.maker.io.output;

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
import io.netty.util.CharsetUtil;

/**
 * Sidecar WebSocket 客户端处理器。
 * 负责处理与 Node.js Sidecar 的 WebSocket 连接，包括 PING/PONG 心跳。
 */
final class SidecarWebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

    private final WebSocketClientHandshaker handshaker;
    private ChannelPromise handshakeFuture;

    SidecarWebSocketClientHandler(WebSocketClientHandshaker handshaker) {
        this.handshaker = handshaker;
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
        if (frame instanceof TextWebSocketFrame) {
            // TODO: 处理来自 Sidecar 的响应消息（订单确认、成交回报等）
            // 可以在这里解析 JSON 并转换为 CoreEvent，发布到 Disruptor
            // TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
        } else if (frame instanceof PingWebSocketFrame pingFrame) {
            // 收到 PING，立即回复 PONG（payload 保持一致）
            ch.writeAndFlush(new PongWebSocketFrame());
        } else if (frame instanceof CloseWebSocketFrame) {
            ch.close();
        }
    }
}

