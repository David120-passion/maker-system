package com.xinyue.maker.io.input.binance;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.CoreEventType;
import com.xinyue.maker.common.Exchange;
import com.lmax.disruptor.RingBuffer;
import com.xinyue.maker.io.MarketDataConnector;
import com.xinyue.maker.io.Normalizer;
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
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.SSLException;
import java.net.URI;

/**
 * Binance 行情连接器，实现参考行情接入。
 */
public final class BinanceMarketDataConnector implements MarketDataConnector {

    private static final URI BINANCE_WS_URI = URI.create("wss://stream.binance.com:443/ws");

    private final Normalizer normalizer;
    private EventLoopGroup eventLoopGroup;
    private Channel channel;

    public BinanceMarketDataConnector(Normalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public boolean referenceOnly() {
        return true;
    }

    @Override
    public synchronized void start() {
        if (channel != null && channel.isActive()) {
            return;
        }
        try {
            bootstrapNetty();
        } catch (SSLException e) {
            throw new IllegalStateException("初始化 Binance WebSocket 失败", e);
        }
    }

    @Override
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

    private void bootstrapNetty() throws SSLException {
        String scheme = BINANCE_WS_URI.getScheme();
        String host = BINANCE_WS_URI.getHost();
        int port = BINANCE_WS_URI.getPort() == -1 ? 443 : BINANCE_WS_URI.getPort();
        boolean ssl = "wss".equalsIgnoreCase(scheme);
        SslContext sslCtx = ssl ? SslContextBuilder.forClient().build() : null;

        eventLoopGroup = new NioEventLoopGroup(1);

        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                BINANCE_WS_URI,
                WebSocketVersion.V13,
                null,
                true,
                new DefaultHttpHeaders()
        );

        BinanceWebSocketClientHandler handler = new BinanceWebSocketClientHandler(handshaker, normalizer, Exchange.BINANCE);

        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (sslCtx != null) {
                            pipeline.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                        }
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

    public void publishMockTick(RingBuffer<CoreEvent> ringBuffer) {
        long seq = ringBuffer.next();
        try {
            CoreEvent event = ringBuffer.get(seq);
            event.type = CoreEventType.MARKET_DATA_TICK;
            event.symbolId = (short) 1;
            event.exchangeId = Exchange.BINANCE.id();
        } finally {
            ringBuffer.publish(seq);
        }
    }
}

