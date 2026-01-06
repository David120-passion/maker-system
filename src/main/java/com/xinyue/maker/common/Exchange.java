package com.xinyue.maker.common;

/**
 * 支持的交易所枚举。
 */
public enum Exchange {
    BINANCE((short) 1, true),
    DYDX((short) 2, false),
    Test((short) 3, true);

    private final short id;
    private final boolean referenceOnly;

    Exchange(short id, boolean referenceOnly) {
        this.id = id;
        this.referenceOnly = referenceOnly;
    }

    public short id() {
        return id;
    }

    public boolean referenceOnly() {
        return referenceOnly;
    }

    public static Exchange fromId(short id) {
        for (Exchange exchange : values()) {
            if (exchange.id == id) {
                return exchange;
            }
        }
        throw new IllegalArgumentException("未知交易所 id=" + id);
    }
}

