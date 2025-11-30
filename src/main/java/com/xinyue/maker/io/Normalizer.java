package com.xinyue.maker.io;

import com.lmax.disruptor.RingBuffer;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;

/**
 * 将交易所原始 JSON 转换成可复用的 CoreEvent。
 */
public final class Normalizer {

    private final RingBuffer<CoreEvent> ringBuffer;

    public Normalizer(RingBuffer<CoreEvent> ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    public void onJsonMessage(Exchange exchange, byte[] payload) {
        long seq = ringBuffer.next();
        try {
            CoreEvent event = ringBuffer.get(seq);
            event.exchangeId = exchange.id();
            // TODO 解析负载并填充事件字段
        } finally {
            ringBuffer.publish(seq);
        }
    }
}

