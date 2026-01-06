package com.xinyue.maker.core.lob;

/**
 * 参考盘与对战盘的占位数据结构。
 * 后续会被预分配的买卖盘数组所取代。
 */
public final class OrderBookSnapshot {

    private long bestBidE8;
    private long bestAskE8;
    private long bestBidQtyE8;  // 买一价深度（放大 1e8）
    private long bestAskQtyE8;  // 卖一价深度（放大 1e8）

    public long bestBidE8() {
        return bestBidE8;
    }

    public void bestBidE8(long value) {
        this.bestBidE8 = value;
    }

    public long bestAskE8() {
        return bestAskE8;
    }

    public void bestAskE8(long value) {
        this.bestAskE8 = value;
    }

    public long bestBidQtyE8() {
        return bestBidQtyE8;
    }

    public void bestBidQtyE8(long value) {
        this.bestBidQtyE8 = value;
    }

    public long bestAskQtyE8() {
        return bestAskQtyE8;
    }

    public void bestAskQtyE8(long value) {
        this.bestAskQtyE8 = value;
    }
}

