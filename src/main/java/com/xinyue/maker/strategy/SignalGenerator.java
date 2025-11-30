package com.xinyue.maker.strategy;

import com.xinyue.maker.core.lob.OrderBookSnapshot;

public final class SignalGenerator {

    public SignalResult generate(OrderBookSnapshot snapshot) {
        return new SignalResult(snapshot.bestBidE8(), snapshot.bestAskE8());
    }

    public record SignalResult(long bidE8, long askE8) {
    }
}

