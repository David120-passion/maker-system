package com.xinyue.maker.core;

import com.lmax.disruptor.EventFactory;
import com.xinyue.maker.common.CoreEvent;

public final class CoreEventFactory implements EventFactory<CoreEvent> {
    @Override
    public CoreEvent newInstance() {
        return new CoreEvent();
    }
}

