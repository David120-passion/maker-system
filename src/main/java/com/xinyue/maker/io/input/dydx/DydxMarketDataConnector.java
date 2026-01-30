package com.xinyue.maker.io.input.dydx;

import com.lmax.disruptor.dsl.Disruptor;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.core.CoreEngine;
import com.xinyue.maker.core.CoreEventFactory;
import com.xinyue.maker.infra.OriginalMessageDao;
import com.xinyue.maker.io.MarketDataConnector;
import com.xinyue.maker.io.Normalizer;
import com.xinyue.maker.io.input.AccessLayerCoordinator;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * dYdX 行情连接器，实现订单簿订阅。
 */
public final class DydxMarketDataConnector implements MarketDataConnector {

    private static final URI DYDX_WS_URI = URI.create("wss://dydx3.forcast.money/v4/ws");

    private final Normalizer normalizer;
    private EventLoopGroup eventLoopGroup;
    private Channel channel;
    private DydxWebSocketClientHandler handler;
    private OriginalMessageDao originalMessageDao;

    // 账户订单订阅配置（支持多个账户）
    private final List<DydxWebSocketClientHandler.AccountSubscription> accountSubscriptions = new ArrayList<>();

    public DydxMarketDataConnector(Normalizer normalizer, OriginalMessageDao originalMessageDao) {
        this.normalizer = normalizer;
        this.originalMessageDao = originalMessageDao;
    }

    @Override
    public Exchange exchange() {
        return Exchange.DYDX;
    }

    @Override
    public boolean referenceOnly() {
        return false; // dYdX 是目标交易所
    }

    @Override
    public synchronized void start() {
        if (channel != null && channel.isActive()) {
            return;
        }
        try {
            bootstrapNetty();
        } catch (SSLException e) {
            throw new IllegalStateException("init dYdX WebSocket fail", e);
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
        String scheme = DYDX_WS_URI.getScheme();
        String host = DYDX_WS_URI.getHost();
        int port = DYDX_WS_URI.getPort() == -1 ? 443 : DYDX_WS_URI.getPort();
        boolean ssl = "wss".equalsIgnoreCase(scheme);
        SslContext sslCtx = ssl ? SslContextBuilder.forClient().build() : null;

        eventLoopGroup = new NioEventLoopGroup(1);

        // 设置 WebSocket 最大帧大小为 10MB（默认 64KB 不够用）
        final int maxFramePayloadLength = 10 * 1024 * 1024; // 10MB
        
        // 创建 handshaker，传入最大帧大小参数
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                DYDX_WS_URI,
                WebSocketVersion.V13,
                null,
                true,
                new DefaultHttpHeaders(),
                maxFramePayloadLength  // 设置最大帧大小
        );


        handler = new DydxWebSocketClientHandler(handshaker, normalizer, Exchange.DYDX, originalMessageDao, accountSubscriptions, maxFramePayloadLength);

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
                        // 增加 HttpObjectAggregator 大小以支持更大的 HTTP 消息（握手阶段）
                        pipeline.addLast(
                                new HttpClientCodec(),
                                new HttpObjectAggregator(maxFramePayloadLength), // 10MB，与 WebSocket 帧大小一致
                                WebSocketClientCompressionHandler.INSTANCE,
                                // 手动添加 WebSocket 帧解码器和编码器，设置最大帧大小
                                // 注意：这些会在握手完成后由 handshaker 自动添加，但我们需要在握手前就设置好
                                // 所以这里先不添加，而是在握手完成后动态添加
                                handler
                        );
                    }
                });

        channel = bootstrap.connect(host, port).syncUninterruptibly().channel();
        handler.handshakeFuture().syncUninterruptibly();
    }

    /**
     * 订阅订单簿（v4_orderbook 频道）。
     *
     * @param symbol 交易对符号（如 "H2-USDT"）
     */
    public void subscribeOrderBook(String symbol) {
        if (channel != null && channel.isActive() && handler != null) {
            handler.subscribeOrderBook(channel, symbol);
        }
    }

    /**
     * 由核心层在检测到 dYdX messageId gap 时调用，触发「取消订阅 + 重新订阅」。
     */
    public void resubscribeOrderBook(String symbol) {
        if (channel != null && channel.isActive() && handler != null) {
            handler.resubscribe(channel, symbol);
        }
    }

    /**
     * 订阅账户订单（v4_subaccounts 频道）。
     * 在项目启动时调用，为每个账户订阅订单更新。
     *
     * @param address 账户地址（如 "h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt"）
     * @param subaccountNumber 子账户编号（通常为 0）
     */
    public void subscribeAccountOrders(String address, int subaccountNumber) {
        if (channel != null && channel.isActive() && handler != null) {
            handler.subscribeAccountOrders(channel, address, subaccountNumber);
        }
    }

    /**
     * 退订订单簿（v4_orderbook 频道）。
     *
     * @param symbol 交易对符号（如 "H2-USDT"）
     */
    public void unsubscribeOrderBook(String symbol) {
        if (channel != null && channel.isActive() && handler != null) {
            handler.unsubscribeOrderBook(channel, symbol);
        }
    }

    /**
     * 退订账户订单（v4_subaccounts 频道）。
     *
     * @param address 账户地址
     * @param subaccountNumber 子账户编号
     */
    public void unsubscribeAccountOrders(String address, int subaccountNumber) {
        if (channel != null && channel.isActive() && handler != null) {
            handler.unsubscribeAccountOrders(channel, address, subaccountNumber);
        }
    }

    /**
     * 在启动前配置需要订阅账户订单的地址/子账户（支持多个账户）。
     * - 如果在握手前调用：握手成功后由 handler 自动发送订阅消息
     * - 如果在握手完成后调用：立即通过现有 channel 发送订阅消息
     */
    public void configureAccountOrders(String address, int subaccountNumber) {
        accountSubscriptions.add(new DydxWebSocketClientHandler.AccountSubscription(address, subaccountNumber));
        if (handler != null) {
            handler.addAccountSubscription(address, subaccountNumber);
        }
    }

    public static void main(String[] args) {
        // 使用真实线程（CPU 密集型作业不适合虚拟线程）
        ThreadFactory threadFactory = new ThreadFactory() {
            private int counter = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "disruptor-" + (counter++));
                t.setDaemon(false);
                return t;
            }
        };

        // 先创建 Disruptor（但先不启动），因为 Normalizer 需要 RingBuffer
        Disruptor<CoreEvent> disruptor = CoreEngine.bootstrapDisruptor(
                new CoreEventFactory(),
                null, // 先传 null，后面再设置 handler
                threadFactory
        );
        Normalizer normalizer = new Normalizer(disruptor.getRingBuffer());
        DydxMarketDataConnector dydxMarketDataConnector = new DydxMarketDataConnector(normalizer, null);
//        dydxMarketDataConnector.configureAccountOrders("h21j6r56vlt5x3j9vvzt9yjl0kf2ke4pachq882kp",0);
        dydxMarketDataConnector.configureAccountOrders("h21pqhys5muk0tpftmvpak87rakycs9vh3r3c0950",0);
        AccessLayerCoordinator accessLayerCoordinator = new AccessLayerCoordinator()
                .register(dydxMarketDataConnector);

        accessLayerCoordinator.startAll();
    }
}
