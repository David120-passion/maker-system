package http;

import com.lmax.disruptor.dsl.Disruptor;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.core.CoreEngine;
import com.xinyue.maker.core.CoreEventFactory;
import com.xinyue.maker.io.Normalizer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadFactory;

public class TestNormalizer {
    public static void main(String[] args) {
        ThreadFactory threadFactory = Thread.ofVirtual().name("disruptor-", 0).factory();
        Disruptor<CoreEvent> disruptor = CoreEngine.bootstrapDisruptor(
                new CoreEventFactory(),
                null, // 先传 null，后面再设置 handler
                threadFactory
        );
        Normalizer normalizer = new Normalizer(disruptor.getRingBuffer());
        normalizer.onJsonMessage(Exchange.DYDX,"{\"type\":\"channel_data\",\"connection_id\":\"aab8294b-ed6d-4430-aefb-4e38b08561af\",\"message_id\":2044,\"id\":\"H2-USDT\",\"channel\":\"v4_orderbook\",\"version\":\"1.0.0\",\"contents\":{\"asks\":[[\"31.06\",\"0\"]]}}".getBytes(StandardCharsets.UTF_8));
    }
}
