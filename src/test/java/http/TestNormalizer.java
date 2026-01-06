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
        normalizer.onJsonMessage(Exchange.DYDX,"{\"type\":\"channel_data\",\"connection_id\":\"4d7dad40-4581-4393-adcf-b7bb6039f4fe\",\"message_id\":48,\"id\":\"h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt/0\",\"channel\":\"v4_subaccounts\",\"version\":\"3.0.0\",\"contents\":{\"fills\":[{\"id\":\"fe35aeb0-6ef3-5a38-939a-cec397062e85\",\"fee\":\"0\",\"side\":\"SELL\",\"size\":\"7.01\",\"type\":\"LIMIT\",\"price\":\"196.5\",\"eventId\":\"000624ec0000000200000002\",\"orderId\":\"4878be56-b8d1-55ee-9c14-1fcda027be03\",\"createdAt\":\"2025-12-11T11:44:25.327Z\",\"liquidity\":\"MAKER\",\"builderFee\":null,\"clobPairId\":\"1000011\",\"marketType\":\"SPOT\",\"quoteAmount\":\"1377.465\",\"spotMarketId\":\"1000011\",\"subaccountId\":\"36cd4759-4a07-5282-9542-f9ab450f0ef4\",\"builderAddress\":null,\"clientMetadata\":\"0\",\"orderRouterFee\":null,\"createdAtHeight\":\"402668\",\"transactionHash\":\"A801D3583037B7011E71ABD0DB0B3319A18C41BDAD663A17683CAAE77D486B07\",\"affiliateRevShare\":\"0\",\"orderRouterAddress\":null,\"ticker\":\"ORCL-USDT\"},{\"id\":\"f2e3e3a9-a64a-5f29-9064-35d5fb496e48\",\"fee\":\"0\",\"side\":\"SELL\",\"size\":\"6.61\",\"type\":\"LIMIT\",\"price\":\"196.5\",\"eventId\":\"000624ec0000000200000005\",\"orderId\":\"4878be56-b8d1-55ee-9c14-1fcda027be03\",\"createdAt\":\"2025-12-11T11:44:25.327Z\",\"liquidity\":\"MAKER\",\"builderFee\":null,\"clobPairId\":\"1000011\",\"marketType\":\"SPOT\",\"quoteAmount\":\"1298.865\",\"spotMarketId\":\"1000011\",\"subaccountId\":\"36cd4759-4a07-5282-9542-f9ab450f0ef4\",\"builderAddress\":null,\"clientMetadata\":\"0\",\"orderRouterFee\":null,\"createdAtHeight\":\"402668\",\"transactionHash\":\"A801D3583037B7011E71ABD0DB0B3319A18C41BDAD663A17683CAAE77D486B07\",\"affiliateRevShare\":\"0\",\"orderRouterAddress\":null,\"ticker\":\"ORCL-USDT\"}],\"blockHeight\":\"402668\",\"orders\":[{\"id\":\"4878be56-b8d1-55ee-9c14-1fcda027be03\",\"side\":\"SELL\",\"size\":\"100\",\"type\":\"LIMIT\",\"price\":\"196.5\",\"feePpm\":null,\"status\":\"OPEN\",\"clientId\":\"533126548\",\"duration\":null,\"interval\":null,\"updatedAt\":\"2025-12-11T11:44:25.327Z\",\"clobPairId\":\"1000011\",\"marketType\":\"SPOT\",\"orderFlags\":\"64\",\"reduceOnly\":false,\"timeInForce\":\"GTT\",\"totalFilled\":\"21.04\",\"goodTilBlock\":null,\"spotMarketId\":\"1000011\",\"subaccountId\":\"36cd4759-4a07-5282-9542-f9ab450f0ef4\",\"triggerPrice\":null,\"builderAddress\":null,\"clientMetadata\":\"0\",\"priceTolerance\":null,\"createdAtHeight\":\"402608\",\"updatedAtHeight\":\"402668\",\"goodTilBlockTime\":\"2026-01-08T11:43:54.000Z\",\"orderRouterAddress\":\"\",\"postOnly\":false,\"ticker\":\"ORCL-USDT\"}]}}\n".getBytes(StandardCharsets.UTF_8));

    }
}
