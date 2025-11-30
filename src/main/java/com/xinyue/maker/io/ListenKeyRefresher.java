package com.xinyue.maker.io;

public final class ListenKeyRefresher {

    private final SessionManager sessionManager;

    public ListenKeyRefresher(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void start() {
        // TODO 调度 REST Keep-Alive 任务维持 ListenKey
    }
}

