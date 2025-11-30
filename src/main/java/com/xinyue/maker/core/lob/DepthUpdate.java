package com.xinyue.maker.core.lob;

import com.xinyue.maker.common.Exchange;

/**
 * 表示单次 order book 增量更新的占位结构。
 */
public final class DepthUpdate {

    private Exchange exchange;
    private short symbolId;
    private long updateId;

    public Exchange exchange() {
        return exchange;
    }

    public DepthUpdate exchange(Exchange value) {
        this.exchange = value;
        return this;
    }

    public short symbolId() {
        return symbolId;
    }

    public DepthUpdate symbolId(short value) {
        this.symbolId = value;
        return this;
    }

    public long updateId() {
        return updateId;
    }

    public DepthUpdate updateId(long value) {
        this.updateId = value;
        return this;
    }
}

