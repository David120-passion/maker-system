package com.xinyue.maker.core;

import com.lmax.disruptor.RingBuffer;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.CoreEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试事件调度器：定期发送 TEST 事件到 Disruptor，用于触发慢速订单测试策略。
 */
public final class TestEventScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(TestEventScheduler.class);

    private final RingBuffer<CoreEvent> ringBuffer;
    private final long minIntervalMs; // 最小发送间隔（毫秒）
    private final long maxIntervalMs; // 最大发送间隔（毫秒）
    private final Random random; // 随机数生成器
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread schedulerThread;
    private final short symbolId; // 交易对 ID（用于多策略路由）

    /**
     * 创建测试事件调度器（固定间隔）。
     *
     * @param ringBuffer Disruptor RingBuffer
     * @param intervalMs 发送间隔（毫秒），建议 1000ms（1秒）
     */
    public TestEventScheduler(RingBuffer<CoreEvent> ringBuffer, long intervalMs) {
        this(ringBuffer, intervalMs, intervalMs, (short) 0);
    }

    /**
     * 创建测试事件调度器（随机间隔）。
     *
     * @param ringBuffer Disruptor RingBuffer
     * @param minIntervalMs 最小发送间隔（毫秒）
     * @param maxIntervalMs 最大发送间隔（毫秒）
     */
    public TestEventScheduler(RingBuffer<CoreEvent> ringBuffer, long minIntervalMs, long maxIntervalMs) {
        this(ringBuffer, minIntervalMs, maxIntervalMs, (short) 0);
    }
    
    /**
     * 创建测试事件调度器（随机间隔，带 symbolId）。
     *
     * @param ringBuffer Disruptor RingBuffer
     * @param minIntervalMs 最小发送间隔（毫秒）
     * @param maxIntervalMs 最大发送间隔（毫秒）
     * @param symbolId 交易对 ID（用于多策略路由）
     */
    public TestEventScheduler(RingBuffer<CoreEvent> ringBuffer, long minIntervalMs, long maxIntervalMs, short symbolId) {
        this.ringBuffer = ringBuffer;
        this.minIntervalMs = minIntervalMs;
        this.maxIntervalMs = maxIntervalMs;
        this.symbolId = symbolId;
        this.random = new Random();
        if (minIntervalMs > maxIntervalMs) {
            throw new IllegalArgumentException("minIntervalMs (" + minIntervalMs + ") 不能大于 maxIntervalMs (" + maxIntervalMs + ")");
        }
    }

    /**
     * 启动定时器线程。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            LOG.warn("TestEventScheduler 已经启动");
            return;
        }

        schedulerThread = new Thread(() -> {
            if (minIntervalMs == maxIntervalMs) {
                LOG.info("TestEventScheduler 启动，固定间隔 {}ms", minIntervalMs);
                System.out.println("TestEventScheduler 启动，固定间隔 " + minIntervalMs + "ms");
            } else {
                LOG.info("TestEventScheduler 启动，随机间隔 {}ms - {}ms", minIntervalMs, maxIntervalMs);
                System.out.println("TestEventScheduler 启动，随机间隔 " + minIntervalMs + "ms - " + maxIntervalMs + "ms");
            }
            int eventCount = 0;
            while (running.get()) {
                try {
                    // 发布 TEST 事件到 RingBuffer
                    long sequence = ringBuffer.next();
                    try {
                        CoreEvent event = ringBuffer.get(sequence);
                        event.reset();
                        event.type = CoreEventType.TEST;
                        event.timestamp = System.currentTimeMillis();
                        event.recvTime = System.nanoTime();
                        event.symbolId = symbolId; // 设置 symbolId 用于多策略路由
                        eventCount++;
                        if (eventCount % 10 == 0) {
                            LOG.debug("已发送 {} 个 TEST 事件（symbolId={}）", eventCount, symbolId);
                        }
                    } finally {
                        ringBuffer.publish(sequence);
                    }

                    // 计算随机间隔并等待
                    long nextIntervalMs;
                    if (minIntervalMs == maxIntervalMs) {
                        nextIntervalMs = minIntervalMs;
                    } else {
                        // 生成 minIntervalMs 到 maxIntervalMs 之间的随机数
                        nextIntervalMs = minIntervalMs + (long) (random.nextDouble() * (maxIntervalMs - minIntervalMs));
                    }
                    Thread.sleep(nextIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.info("TestEventScheduler 被中断");
                    break;
                } catch (Exception e) {
                    LOG.error("TestEventScheduler 发送事件失败", e);
                }
            }
            LOG.info("TestEventScheduler 停止");
        }, "test-event-scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
    }

    /**
     * 停止定时器线程。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (schedulerThread != null) {
            schedulerThread.interrupt();
            try {
                schedulerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("TestEventScheduler 已停止");
    }
}

