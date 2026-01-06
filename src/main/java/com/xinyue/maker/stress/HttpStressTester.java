package com.xinyue.maker.stress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * HTTP 压力测试工具
 * <p>
 * 支持多线程并发请求，统计响应时间、成功率等指标。
 */
public class HttpStressTester {
    
    private final String url;
    private final String method;
    private final String body;
    private final Map<String, String> headers;
    private final int concurrency;
    private final int totalRequests;
    private final Duration timeout;
    
    private final HttpClient httpClient;
    private final ExecutorService executorService;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final List<Long> responseTimes = new CopyOnWriteArrayList<>();
    private final List<Integer> statusCodes = new CopyOnWriteArrayList<>();
    
    private volatile boolean running = false;
    private Instant startTime;
    private Instant endTime;
    
    /**
     * 构造压力测试器
     * 
     * @param url 目标 URL（支持 http:// 或 https://）
     * @param method HTTP 方法（GET, POST, PUT, DELETE 等）
     * @param body 请求体（POST/PUT 时使用，GET 时可为 null）
     * @param headers 自定义请求头（可为 null）
     * @param concurrency 并发线程数
     * @param totalRequests 总请求数
     * @param timeout 请求超时时间（秒）
     */
    public HttpStressTester(String url, String method, String body, 
                           Map<String, String> headers,
                           int concurrency, int totalRequests, int timeout) {
        this.url = url;
        this.method = method.toUpperCase();
        this.body = body;
        this.headers = headers;
        this.concurrency = concurrency;
        this.totalRequests = totalRequests;
        this.timeout = Duration.ofSeconds(timeout);
        
        // 创建 HttpClient（使用虚拟线程池，Java 21 特性）
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        
        // 创建线程池用于协调任务
        this.executorService = Executors.newFixedThreadPool(concurrency);
    }
    
    /**
     * 执行压力测试
     */
    public StressTestResult run() throws InterruptedException {
        if (running) {
            throw new IllegalStateException("压力测试正在运行中");
        }
        
        running = true;
        startTime = Instant.now();
        
        System.out.println("=========================================");
        System.out.println("开始压力测试");
        System.out.println("目标 URL: " + url);
        System.out.println("HTTP 方法: " + method);
        System.out.println("并发数: " + concurrency);
        System.out.println("总请求数: " + totalRequests);
        System.out.println("超时时间: " + timeout.getSeconds() + " 秒");
        System.out.println("=========================================");
        
        // 使用 CountDownLatch 等待所有请求完成
        CountDownLatch latch = new CountDownLatch(totalRequests);
        Semaphore semaphore = new Semaphore(concurrency);
        
        // 启动统计线程
        ScheduledExecutorService statsExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> statsFuture = statsExecutor.scheduleAtFixedRate(
                this::printStats, 1, 1, TimeUnit.SECONDS);
        
        // 提交所有请求任务
        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i;
            executorService.submit(() -> {
                try {
                    semaphore.acquire();
                    executeRequest(requestId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.err.println("请求 " + requestId + " 异常: " + e.getMessage());
                } finally {
                    semaphore.release();
                    latch.countDown();
                }
            });
        }
        
        // 等待所有请求完成
        latch.await();
        
        // 停止统计线程
        statsFuture.cancel(false);
        statsExecutor.shutdown();
        
        endTime = Instant.now();
        running = false;
        
        // 生成结果
        return buildResult();
    }
    
    /**
     * 执行单个 HTTP 请求
     */
    private void executeRequest(int requestId) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout);
            
            // 设置请求方法
            if ("GET".equals(method)) {
                requestBuilder.GET();
            } else if ("POST".equals(method)) {
                requestBuilder.POST(body == null ? 
                        HttpRequest.BodyPublishers.noBody() : 
                        HttpRequest.BodyPublishers.ofString(body));
            } else if ("PUT".equals(method)) {
                requestBuilder.PUT(body == null ? 
                        HttpRequest.BodyPublishers.noBody() : 
                        HttpRequest.BodyPublishers.ofString(body));
            } else if ("DELETE".equals(method)) {
                requestBuilder.DELETE();
            } else {
                requestBuilder.method(method, body == null ? 
                        HttpRequest.BodyPublishers.noBody() : 
                        HttpRequest.BodyPublishers.ofString(body));
            }
            
            // 设置请求头
            if (headers != null) {
                headers.forEach(requestBuilder::header);
            }
            
            HttpRequest request = requestBuilder.build();
            
            // 发送请求并测量时间
            long startNanos = System.nanoTime();
            HttpResponse<String> response = httpClient.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            long duration = System.nanoTime() - startNanos;
            long durationMs = duration / 1_000_000;
            
            // 记录结果
            int statusCode = response.statusCode();
            statusCodes.add(statusCode);
            
            if (statusCode >= 200 && statusCode < 300) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }
            
            responseTimes.add(durationMs);
            totalResponseTime.addAndGet(durationMs);
            
        } catch (Exception e) {
            failureCount.incrementAndGet();
            responseTimes.add(-1L); // 标记为失败
        }
    }
    
    /**
     * 打印实时统计信息
     */
    private void printStats() {
        int completed = successCount.get() + failureCount.get();
        int success = successCount.get();
        int failed = failureCount.get();
        double progress = (double) completed / totalRequests * 100;
        
        long avgResponseTime = completed > 0 ? totalResponseTime.get() / completed : 0;
        
        System.out.printf("[实时统计] 已完成: %d/%d (%.1f%%) | 成功: %d | 失败: %d | 平均响应时间: %d ms%n",
                completed, totalRequests, progress, success, failed, avgResponseTime);
    }
    
    /**
     * 构建测试结果
     */
    private StressTestResult buildResult() {
        long durationMs = Duration.between(startTime, endTime).toMillis();
        int total = successCount.get() + failureCount.get();
        int success = successCount.get();
        int failed = failureCount.get();
        
        // 计算响应时间统计
        List<Long> sortedResponseTimes = new ArrayList<>(responseTimes);
        sortedResponseTimes.removeIf(t -> t < 0); // 移除失败的请求
        sortedResponseTimes.sort(Long::compareTo);
        
        long minResponseTime = sortedResponseTimes.isEmpty() ? 0 : sortedResponseTimes.get(0);
        long maxResponseTime = sortedResponseTimes.isEmpty() ? 0 : 
                sortedResponseTimes.get(sortedResponseTimes.size() - 1);
        long avgResponseTime = sortedResponseTimes.isEmpty() ? 0 : 
                sortedResponseTimes.stream().mapToLong(Long::longValue).sum() / sortedResponseTimes.size();
        
        long p50 = percentile(sortedResponseTimes, 50);
        long p90 = percentile(sortedResponseTimes, 90);
        long p95 = percentile(sortedResponseTimes, 95);
        long p99 = percentile(sortedResponseTimes, 99);
        
        // 计算 QPS
        double qps = durationMs > 0 ? (double) total / durationMs * 1000 : 0;
        
        // 统计状态码分布
        Map<Integer, Long> statusCodeDistribution = statusCodes.stream()
                .collect(Collectors.groupingBy(code -> code, Collectors.counting()));
        
        return new StressTestResult(
                url, method, concurrency, totalRequests,
                durationMs, total, success, failed,
                minResponseTime, maxResponseTime, avgResponseTime,
                p50, p90, p95, p99, qps, statusCodeDistribution
        );
    }
    
    /**
     * 计算百分位数
     */
    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
    
    /**
     * 关闭资源
     */
    public void shutdown() {
        running = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

