package com.xinyue.maker.stress;

import java.util.Map;

/**
 * 压力测试结果
 */
public class StressTestResult {
    
    private final String url;
    private final String method;
    private final int concurrency;
    private final int totalRequests;
    private final long durationMs;
    private final int totalCompleted;
    private final int successCount;
    private final int failureCount;
    private final long minResponseTime;
    private final long maxResponseTime;
    private final long avgResponseTime;
    private final long p50ResponseTime;
    private final long p90ResponseTime;
    private final long p95ResponseTime;
    private final long p99ResponseTime;
    private final double qps;
    private final Map<Integer, Long> statusCodeDistribution;
    
    public StressTestResult(String url, String method, int concurrency, int totalRequests,
                           long durationMs, int totalCompleted, int successCount, int failureCount,
                           long minResponseTime, long maxResponseTime, long avgResponseTime,
                           long p50ResponseTime, long p90ResponseTime, long p95ResponseTime,
                           long p99ResponseTime, double qps,
                           Map<Integer, Long> statusCodeDistribution) {
        this.url = url;
        this.method = method;
        this.concurrency = concurrency;
        this.totalRequests = totalRequests;
        this.durationMs = durationMs;
        this.totalCompleted = totalCompleted;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.minResponseTime = minResponseTime;
        this.maxResponseTime = maxResponseTime;
        this.avgResponseTime = avgResponseTime;
        this.p50ResponseTime = p50ResponseTime;
        this.p90ResponseTime = p90ResponseTime;
        this.p95ResponseTime = p95ResponseTime;
        this.p99ResponseTime = p99ResponseTime;
        this.qps = qps;
        this.statusCodeDistribution = statusCodeDistribution;
    }
    
    /**
     * 打印测试结果
     */
    public void print() {
        System.out.println("\n=========================================");
        System.out.println("压力测试结果");
        System.out.println("=========================================");
        System.out.println("目标 URL: " + url);
        System.out.println("HTTP 方法: " + method);
        System.out.println("并发数: " + concurrency);
        System.out.println("总请求数: " + totalRequests);
        System.out.println("已完成请求数: " + totalCompleted);
        System.out.println();
        System.out.println("--- 耗时统计 ---");
        System.out.println("总耗时: " + durationMs + " ms (" + (durationMs / 1000.0) + " 秒)");
        System.out.println();
        System.out.println("--- 成功率统计 ---");
        System.out.println("成功: " + successCount + " (" + 
                String.format("%.2f", (double) successCount / totalCompleted * 100) + "%)");
        System.out.println("失败: " + failureCount + " (" + 
                String.format("%.2f", (double) failureCount / totalCompleted * 100) + "%)");
        System.out.println();
        System.out.println("--- 响应时间统计 (ms) ---");
        System.out.println("最小: " + minResponseTime);
        System.out.println("最大: " + maxResponseTime);
        System.out.println("平均: " + avgResponseTime);
        System.out.println("P50: " + p50ResponseTime);
        System.out.println("P90: " + p90ResponseTime);
        System.out.println("P95: " + p95ResponseTime);
        System.out.println("P99: " + p99ResponseTime);
        System.out.println();
        System.out.println("--- 性能指标 ---");
        System.out.println("QPS (每秒请求数): " + String.format("%.2f", qps));
        System.out.println();
        System.out.println("--- HTTP 状态码分布 ---");
        statusCodeDistribution.forEach((code, count) -> 
                System.out.println(code + ": " + count + " 次"));
        System.out.println("=========================================\n");
    }
    
    // Getters
    public String url() { return url; }
    public String method() { return method; }
    public int concurrency() { return concurrency; }
    public int totalRequests() { return totalRequests; }
    public long durationMs() { return durationMs; }
    public int totalCompleted() { return totalCompleted; }
    public int successCount() { return successCount; }
    public int failureCount() { return failureCount; }
    public long minResponseTime() { return minResponseTime; }
    public long maxResponseTime() { return maxResponseTime; }
    public long avgResponseTime() { return avgResponseTime; }
    public long p50ResponseTime() { return p50ResponseTime; }
    public long p90ResponseTime() { return p90ResponseTime; }
    public long p95ResponseTime() { return p95ResponseTime; }
    public long p99ResponseTime() { return p99ResponseTime; }
    public double qps() { return qps; }
    public Map<Integer, Long> statusCodeDistribution() { return statusCodeDistribution; }
}

