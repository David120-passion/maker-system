package com.xinyue.maker.io.output;

import com.xinyue.maker.common.Exchange;

public class TradeSession {
    // === 身份标识 ===
    public final int accountId;      // 对应 L2/L3 的 ID
    public final String accountName; // 日志打印用 (如 "Main_Hype")
    public final Exchange exchange; // BINANCE 或 HYPERLIQUID
    public final String mnemonicPhrase;


    public TradeSession(int id, String name, Exchange type,String mnemonicPhrase) {
        this.accountId = id;
        this.accountName = name;
        this.exchange = type;
        this.mnemonicPhrase = mnemonicPhrase;
    }

}