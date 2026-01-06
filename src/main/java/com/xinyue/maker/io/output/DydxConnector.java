package com.xinyue.maker.io.output;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;

import java.net.URI;

/**
 * Sidecar WebSocket 连接器，负责连接到 Node.js Sidecar 服务。
 * 用于传输下单、取消订单等操作指令。
 */
public final class DydxConnector {

    private final URI sidecarUri;
    private EventLoopGroup eventLoopGroup;
    private Channel channel;
    private SidecarWebSocketClientHandler handler;

    public DydxConnector(String uri) {
        this.sidecarUri = URI.create(uri);
    }

    /**
     * 启动连接。
     */
    public synchronized void start() {
        if (channel != null && channel.isActive()) {
            return;
        }
        try {
            bootstrapNetty();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 Sidecar WebSocket 连接失败", e);
        }
    }

    /**
     * 停止连接。
     */
    public synchronized void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
            eventLoopGroup = null;
        }
    }

    /**
     * 获取 WebSocket Channel，用于发送消息。
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * 检查连接是否活跃。
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    private void bootstrapNetty() {
        String host = sidecarUri.getHost();
        int port = sidecarUri.getPort() == -1 ? 8080 : sidecarUri.getPort();
        // 注意：本地 Sidecar 通常使用 ws://，不需要 SSL

        eventLoopGroup = new NioEventLoopGroup(1);

        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                sidecarUri,
                WebSocketVersion.V13,
                null,
                true,
                new DefaultHttpHeaders()
        );

        handler = new SidecarWebSocketClientHandler(handshaker);

        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 注意：Sidecar 在本地，通常不需要 SSL
                        pipeline.addLast(
                                new HttpClientCodec(),
                                new HttpObjectAggregator(8192),
                                WebSocketClientCompressionHandler.INSTANCE,
                                handler
                        );
                    }
                });

        channel = bootstrap.connect(host, port).syncUninterruptibly().channel();
        handler.handshakeFuture().syncUninterruptibly();
    }

    public static void main(String[] args) {
        DydxConnector sidecarConnector = new DydxConnector("ws://127.0.0.1:8080");
        sidecarConnector.start();
        Channel channel = sidecarConnector.getChannel();
        channel.writeAndFlush(new TextWebSocketFrame("hello"));
    }
}

