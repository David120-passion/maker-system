package com.xinyue.maker.core;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.io.AccessLayerCoordinator;
import com.xinyue.maker.io.GapDetector;
import com.xinyue.maker.io.ListenKeyRefresher;

import java.util.concurrent.ThreadFactory;

/**
 * 负责将 Disruptor 事件流水线与接入层组件对接。
 */
public final class CoreEngine {

    private final Disruptor<CoreEvent> disruptor;
    private final GapDetector gapDetector;
    private final AccessLayerCoordinator accessLayerCoordinator;
    private final ListenKeyRefresher listenKeyRefresher;

    public CoreEngine(Disruptor<CoreEvent> disruptor,
                      GapDetector gapDetector,
                      AccessLayerCoordinator accessLayerCoordinator,
                      ListenKeyRefresher listenKeyRefresher) {
        this.disruptor = disruptor;
        this.gapDetector = gapDetector;
        this.accessLayerCoordinator = accessLayerCoordinator;
        this.listenKeyRefresher = listenKeyRefresher;
    }

    public void start() {
        disruptor.start();
        accessLayerCoordinator.startAll();
        listenKeyRefresher.start();
        gapDetector.start();
    }

    public RingBuffer<CoreEvent> ringBuffer() {
        return disruptor.getRingBuffer();
    }

    public static Disruptor<CoreEvent> bootstrapDisruptor(CoreEventFactory factory,
                                                          EventHandler<CoreEvent> handler,
                                                          ThreadFactory threadFactory) {
        Disruptor<CoreEvent> disruptor = new Disruptor<CoreEvent>(factory, 1024, threadFactory);
        disruptor.handleEventsWith(handler);
        return disruptor;
    }
}

