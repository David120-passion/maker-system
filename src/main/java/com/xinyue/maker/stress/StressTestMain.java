package com.xinyue.maker.stress;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 压力测试主程序
 * <p>
 * 使用示例：
 * <pre>
 * // 简单 GET 请求测试
 * java -cp ... StressTestMain http://example.com/api/test GET 50 1000 10
 * 
 * // POST 请求测试（带请求体）
 * java -cp ... StressTestMain http://example.com/api/test POST 50 1000 10 '{"key":"value"}'
 * </pre>
 */
public class StressTestMain {
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        // 交互式模式
        if (args.length == 1 && "interactive".equalsIgnoreCase(args[0])) {
            interactive();
            return;
        }
        
        if (args.length < 5) {
            printUsage();
            return;
        }
        
        String url = args[0];
        String method = args[1].toUpperCase();
        int concurrency = Integer.parseInt(args[2]);
        int totalRequests = Integer.parseInt(args[3]);
        int timeout = Integer.parseInt(args[4]);
        String body = args.length > 5 ? args[5] : null;
        
        // 可选：从环境变量读取自定义请求头
        Map<String, String> headers = new HashMap<>();
        String contentType = System.getenv("STRESS_CONTENT_TYPE");
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        String authToken = System.getenv("STRESS_AUTH_TOKEN");
        if (authToken != null) {
            headers.put("Authorization", authToken);
        }
        
        // 如果没有设置 Content-Type 且是 POST/PUT，默认设置为 application/json
        if ((method.equals("POST") || method.equals("PUT")) && 
            !headers.containsKey("Content-Type") && body != null) {
            headers.put("Content-Type", "application/json");
        }
        
        HttpStressTester tester = new HttpStressTester(
                url, method, body, headers, concurrency, totalRequests, timeout);
        
        try {
            StressTestResult result = tester.run();
            result.print();
        } catch (InterruptedException e) {
            System.err.println("压力测试被中断");
            Thread.currentThread().interrupt();
        } finally {
            tester.shutdown();
        }
    }
    
    /**
     * 交互式模式
     */
    public static void interactive() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=========================================");
        System.out.println("HTTP 压力测试工具（交互式模式）");
        System.out.println("=========================================\n");
        
        System.out.print("请输入目标 URL: ");
        String url = scanner.nextLine().trim();
        
        System.out.print("请输入 HTTP 方法 (GET/POST/PUT/DELETE) [默认: GET]: ");
        String method = scanner.nextLine().trim();
        if (method.isEmpty()) {
            method = "GET";
        }
        
        System.out.print("请输入并发数 [默认: 50]: ");
        String concurrencyStr = scanner.nextLine().trim();
        int concurrency = concurrencyStr.isEmpty() ? 50 : Integer.parseInt(concurrencyStr);
        
        System.out.print("请输入总请求数 [默认: 1000]: ");
        String totalRequestsStr = scanner.nextLine().trim();
        int totalRequests = totalRequestsStr.isEmpty() ? 1000 : Integer.parseInt(totalRequestsStr);
        
        System.out.print("请输入请求超时时间（秒） [默认: 10]: ");
        String timeoutStr = scanner.nextLine().trim();
        int timeout = timeoutStr.isEmpty() ? 10 : Integer.parseInt(timeoutStr);
        
        String body = null;
        if (method.equals("POST") || method.equals("PUT")) {
            System.out.print("请输入请求体（可选，按 Enter 跳过）: ");
            body = scanner.nextLine().trim();
            if (body.isEmpty()) {
                body = null;
            }
        }
        
        Map<String, String> headers = new HashMap<>();
        System.out.print("是否添加自定义请求头？(y/n) [默认: n]: ");
        String addHeaders = scanner.nextLine().trim();
        if (addHeaders.equalsIgnoreCase("y")) {
            System.out.print("请输入 Content-Type [默认: application/json]: ");
            String contentType = scanner.nextLine().trim();
            if (!contentType.isEmpty()) {
                headers.put("Content-Type", contentType);
            } else if (body != null) {
                headers.put("Content-Type", "application/json");
            }
            
            System.out.print("请输入 Authorization Token（可选，按 Enter 跳过）: ");
            String authToken = scanner.nextLine().trim();
            if (!authToken.isEmpty()) {
                headers.put("Authorization", authToken);
            }
        } else if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        
        System.out.println("\n开始压力测试...\n");
        
        HttpStressTester tester = new HttpStressTester(
                url, method, body, headers, concurrency, totalRequests, timeout);
        
        try {
            StressTestResult result = tester.run();
            result.print();
        } catch (InterruptedException e) {
            System.err.println("压力测试被中断");
            Thread.currentThread().interrupt();
        } finally {
            tester.shutdown();
        }
        
        scanner.close();
    }
    
    private static void printUsage() {
        System.out.println("=========================================");
        System.out.println("HTTP 压力测试工具");
        System.out.println("=========================================\n");
        System.out.println("命令行模式：");
        System.out.println("  java StressTestMain <URL> <METHOD> <CONCURRENCY> <TOTAL_REQUESTS> <TIMEOUT> [BODY]");
        System.out.println();
        System.out.println("参数说明：");
        System.out.println("  URL              - 目标 URL（必需）");
        System.out.println("  METHOD           - HTTP 方法：GET/POST/PUT/DELETE（必需）");
        System.out.println("  CONCURRENCY      - 并发线程数（必需）");
        System.out.println("  TOTAL_REQUESTS   - 总请求数（必需）");
        System.out.println("  TIMEOUT          - 请求超时时间（秒）（必需）");
        System.out.println("  BODY             - 请求体（可选，POST/PUT 时使用）");
        System.out.println();
        System.out.println("环境变量：");
        System.out.println("  STRESS_CONTENT_TYPE - 自定义 Content-Type");
        System.out.println("  STRESS_AUTH_TOKEN   - 自定义 Authorization Token");
        System.out.println();
        System.out.println("示例：");
        System.out.println("  # GET 请求测试");
        System.out.println("  java StressTestMain http://example.com/api/test GET 50 1000 10");
        System.out.println();
        System.out.println("  # POST 请求测试");
        System.out.println("  java StressTestMain http://example.com/api/test POST 50 1000 10 '{\"key\":\"value\"}'");
        System.out.println();
        System.out.println("交互式模式：");
        System.out.println("  java StressTestMain interactive");
        System.out.println("=========================================\n");
    }
}

