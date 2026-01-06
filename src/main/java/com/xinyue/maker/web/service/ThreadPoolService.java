package com.xinyue.maker.web.service;

import org.noear.solon.annotation.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 公共线程池服务。
 * 提供应用级别的共享线程池，避免每个组件都创建自己的线程池。
 */
@Component
public class ThreadPoolService {

    private static final Logger LOG = LoggerFactory.getLogger(ThreadPoolService.class);

    /**
     * 余额查询专用线程池（固定10个线程）。
     * 用于批量查询账户余额等IO密集型任务。
     */
    private final ExecutorService balanceQueryExecutor;

    public ThreadPoolService() {
        // 创建余额查询线程池
        balanceQueryExecutor = Executors.newFixedThreadPool(10, new ThreadFactory() {
            private int counter = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "common-thread-" + (counter++));
                t.setDaemon(true);
                return t;
            }
        });
        LOG.info("ThreadPoolService 初始化完成: balanceQueryExecutor (10 threads)");
    }

    /**
     * 获取余额查询线程池。
     * 
     * @return 余额查询专用线程池
     */
    public ExecutorService getBalanceQueryExecutor() {
        return balanceQueryExecutor;
    }

    /**
     * 关闭所有线程池（应用关闭时调用）。
     */
    public void shutdown() {
        LOG.info("正在关闭 ThreadPoolService...");
        if (balanceQueryExecutor != null && !balanceQueryExecutor.isShutdown()) {
            balanceQueryExecutor.shutdown();
            try {
                if (!balanceQueryExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    balanceQueryExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                balanceQueryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("ThreadPoolService 已关闭");
    }
}

