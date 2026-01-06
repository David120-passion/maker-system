package com.xinyue.maker.core.position;

public class Asset {
    // 使用 long 存储，放大 1e8 (例如 1.5 BTC = 150_000_000)
    public long free;
    public long locked;

    public long total() {
        return free + locked;
    }

    public void clear() {
        free = 0;
        locked = 0;
    }
}