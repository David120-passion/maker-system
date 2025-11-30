package com.xinyue.maker.io;

public final class TradeSession {

    private final short accountId;

    public TradeSession(short accountId) {
        this.accountId = accountId;
    }

    public short accountId() {
        return accountId;
    }
}

