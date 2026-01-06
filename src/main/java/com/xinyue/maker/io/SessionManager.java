package com.xinyue.maker.io;

import com.xinyue.maker.config.DynamicConfigService;
import com.xinyue.maker.io.output.TradeSession;

import java.util.ArrayList;
import java.util.List;

public final class SessionManager {

    private final DynamicConfigService configService;
    private final List<TradeSession> sessions = new ArrayList<>();

    public SessionManager(DynamicConfigService configService) {
        this.configService = configService;
    }

//    public TradeSession acquire(int accountId) {
//        return sessions.stream()
//                .filter(session -> session.accountId == accountId)
//                .findFirst()
//                .orElseGet(() -> {
//                    TradeSession session = new TradeSession(accountId);
//                    sessions.add(session);
//                    return session;
//                });
//    }
}

