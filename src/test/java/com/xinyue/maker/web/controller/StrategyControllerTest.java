package com.xinyue.maker.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinyue.maker.common.ScaleConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StrategyController HTTP 集成测试
 * 使用实际的 HTTP 请求测试接口
 * 
 * 注意：运行此测试前需要先启动 Solon Web 服务器
 * 可以通过运行 WebApp.main() 或 MakerSystemApp.main() 启动服务器
 */
@DisplayName("策略控制器 HTTP 集成测试")
class StrategyControllerTest {

    private static final String BASE_URL = "http://localhost:8090";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("成功启动策略 - 使用完整参数")
    void testStartStrategy_WithAllParameters_Success() throws Exception {
        // 准备测试数据
        Map<String, Object> params = new HashMap<>();
        params.put("buyAccountIds", new int[]{1, 2, 3, 4, 5});
        params.put("sellAccountIds", new int[]{6, 7, 8, 9, 10});
        params.put("minPriceE8", 30L * ScaleConstants.SCALE_E8);
        params.put("maxPriceE8", 45L * ScaleConstants.SCALE_E8);
        params.put("tickSizeE8", 1000000L);
        params.put("volatilityPercent", 3.5);
        params.put("symbolId", 5);
        params.put("baseAssetId", "H2");
        params.put("quoteAssetId", "USDT");
        params.put("exchangeId", 2);
        params.put("cycleDurationMs", 3L * 3600L * 1000L);
        params.put("targetVolumeE8", 500L * ScaleConstants.SCALE_E8);
        params.put("triggerIntervalMs", 3000L);
        params.put("enableVolumeTarget", true);
        params.put("makerCounts", 6);
        params.put("noiseFactory", 0.5);
        params.put("minIntervalMs", 3000L);
        params.put("maxIntervalMs", 6000L);

        // 发送 HTTP 请求
        HttpResponse<String> response = sendPostRequest("/api/strategy/start", params);

        // 验证响应
        assertEquals(200, response.statusCode());
        
        // 解析响应
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        
        // 验证结果
        assertEquals(200, result.get("code"));
        assertNotNull(result.get("message"));
        System.out.println("响应: " + response.body());
    }

    @Test
    @DisplayName("成功启动策略 - 使用默认值")
    void testStartStrategy_WithDefaultValues_Success() throws Exception {
        // 准备测试数据（只传部分参数，其他使用默认值）
        Map<String, Object> params = new HashMap<>();
        params.put("volatilityPercent", 2.5);

        // 发送 HTTP 请求
        HttpResponse<String> response = sendPostRequest("/api/strategy/start", params);

        // 验证响应
        assertEquals(200, response.statusCode());
        
        // 解析响应
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        
        // 验证结果
        assertEquals(200, result.get("code"));
        assertNotNull(result.get("message"));
        System.out.println("响应: " + response.body());
    }

    @Test
    @DisplayName("成功启动策略 - 空参数使用默认值")
    void testStartStrategy_WithEmptyParams_UsesDefaults() throws Exception {
        // 准备测试数据（空参数）
        Map<String, Object> params = new HashMap<>();

        // 发送 HTTP 请求
        HttpResponse<String> response = sendPostRequest("/api/strategy/start", params);

        // 验证响应
        assertEquals(200, response.statusCode());
        
        // 解析响应
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        
        // 验证结果
        assertEquals(200, result.get("code"));
        assertNotNull(result.get("message"));
        System.out.println("响应: " + response.body());
    }

    @Test
    @DisplayName("停止策略")
    void testStopStrategy() throws Exception {
        // 发送 HTTP 请求
        HttpResponse<String> response = sendPostRequest("/api/strategy/stop", new HashMap<>());

        // 验证响应
        assertEquals(200, response.statusCode());
        
        // 解析响应
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        
        // 验证结果
        assertEquals(200, result.get("code"));
        assertNotNull(result.get("message"));
        System.out.println("响应: " + response.body());
    }

    @Test
    @DisplayName("获取策略状态")
    void testGetStatus() throws Exception {
        // 发送 HTTP GET 请求
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/strategy/status"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 验证响应
        assertEquals(200, response.statusCode());
        
        // 解析响应
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        
        // 验证结果
        assertEquals(200, result.get("code"));
        assertNotNull(result.get("data"));
        System.out.println("响应: " + response.body());
    }

    @Test
    @DisplayName("参数解析测试 - 字符串类型的数字")
    void testStartStrategy_StringNumberParams_ParsedCorrectly() throws Exception {
        // 准备测试数据（使用字符串类型的数字）
        Map<String, Object> params = new HashMap<>();
        params.put("minPriceE8", "3000000000"); // 字符串
        params.put("maxPriceE8", "4500000000"); // 字符串
        params.put("volatilityPercent", "3.5"); // 字符串
        params.put("makerCounts", "10"); // 字符串
        params.put("enableVolumeTarget", "true"); // 字符串

        // 发送 HTTP 请求
        HttpResponse<String> response = sendPostRequest("/api/strategy/start", params);

        // 验证响应
        assertEquals(200, response.statusCode());
        
        // 解析响应
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        
        // 验证结果
        assertEquals(200, result.get("code"));
        System.out.println("响应: " + response.body());
    }

    @Test
    @DisplayName("参数解析测试 - 可选参数 minIntervalMs 和 maxIntervalMs")
    void testStartStrategy_OptionalIntervalParams_HandledCorrectly() throws Exception {
        // 准备测试数据（包含可选参数）
        Map<String, Object> params = new HashMap<>();
        params.put("volatilityPercent", 3.0);
        params.put("minIntervalMs", 2000L);
        params.put("maxIntervalMs", 5000L);

        // 发送 HTTP 请求
        HttpResponse<String> response = sendPostRequest("/api/strategy/start", params);

        // 验证响应
        assertEquals(200, response.statusCode());
        
        // 解析响应
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        
        // 验证结果
        assertEquals(200, result.get("code"));
        System.out.println("响应: " + response.body());
    }

    @Test
    @DisplayName("完整流程测试 - 启动 -> 查询状态 -> 停止")
    void testFullFlow_Start_Status_Stop() throws Exception {
        // 1. 启动策略
        Map<String, Object> startParams = new HashMap<>();
        startParams.put("volatilityPercent", 3.0);
        
        HttpResponse<String> startResponse = sendPostRequest("/api/strategy/start", startParams);
        assertEquals(200, startResponse.statusCode());
        Map<String, Object> startResult = objectMapper.readValue(startResponse.body(), new TypeReference<Map<String, Object>>() {});
        System.out.println("启动策略响应: " + startResponse.body());
        
        // 等待一下
        Thread.sleep(1000);
        
        // 2. 查询状态
        HttpRequest statusRequest = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/strategy/status"))
            .GET()
            .build();
        HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusResponse.statusCode());
        Map<String, Object> statusResult = objectMapper.readValue(statusResponse.body(), new TypeReference<Map<String, Object>>() {});
        System.out.println("查询状态响应: " + statusResponse.body());
        
        // 3. 停止策略
        HttpResponse<String> stopResponse = sendPostRequest("/api/strategy/stop", new HashMap<>());
        assertEquals(200, stopResponse.statusCode());
        Map<String, Object> stopResult = objectMapper.readValue(stopResponse.body(), new TypeReference<Map<String, Object>>() {});
        System.out.println("停止策略响应: " + stopResponse.body());
        
        // 验证所有操作都成功
        assertEquals(200, startResult.get("code"));
        assertEquals(200, statusResult.get("code"));
        assertEquals(200, stopResult.get("code"));
    }

    /**
     * 发送 POST 请求的辅助方法
     */
    private HttpResponse<String> sendPostRequest(String path, Map<String, Object> params) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(params);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
