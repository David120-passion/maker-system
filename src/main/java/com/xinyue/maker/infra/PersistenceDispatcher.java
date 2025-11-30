package com.xinyue.maker.infra;

import com.xinyue.maker.common.CoreEvent;

/**
 * 异步将事件写入 Chronicle Queue / Timescale 持久化链路。
 */
public final class PersistenceDispatcher {

    public void publish(CoreEvent event) {
        // TODO 序列化事件并写入 Chronicle Queue
    }
}

