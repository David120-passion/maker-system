package com.xinyue.maker.io;

import com.xinyue.maker.core.lob.LobManager;

public final class GapDetector {

    private final LobManager lobManager;
    private final AccessLayerCoordinator coordinator;

    public GapDetector(LobManager lobManager, AccessLayerCoordinator coordinator) {
        this.lobManager = lobManager;
        this.coordinator = coordinator;
    }

    public void start() {
        // TODO 调度序列号检测与补线流程
    }
}

