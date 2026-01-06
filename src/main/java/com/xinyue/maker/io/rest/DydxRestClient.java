package com.xinyue.maker.io.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * dYdX REST API 客户端。
 * 用于查询账户资产信息等操作。
 */
public final class DydxRestClient {

    private static final String BASE_URL = "https://dydx1.forcast.money";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DydxRestClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 查询子账户信息。
     *
     * @param address 账户地址
     * @param subaccountNumber 子账户编号
     * @return JSON 响应字符串
     * @throws IOException 网络或解析错误
     * @throws InterruptedException 中断异常
     */
    public String getSubaccount(String address, int subaccountNumber) throws IOException, InterruptedException {
        String url = String.format("%s/v4/addresses/%s/subaccountNumber/%d", BASE_URL, address, subaccountNumber);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("dYdX API 请求失败: status=" + response.statusCode() + ", body=" + response.body());
        }
        return response.body();
    }

    /**
     * 解析子账户 JSON，提取资产信息。
     *
     * @param json JSON 字符串
     * @return 资产信息数组，每个元素包含 [symbol, size]（size 为字符串，需要转换为 long）
     * @throws IOException JSON 解析错误
     */
    public AssetInfo[] parseAssetPositions(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode subaccount = root.get("subaccount");
        if (subaccount == null) {
            return new AssetInfo[0];
        }

        JsonNode assetPositions = subaccount.get("assetPositions");
        if (assetPositions == null || !assetPositions.isObject()) {
            return new AssetInfo[0];
        }

        // 计算资产数量
        int count = assetPositions.size();
        AssetInfo[] assets = new AssetInfo[count];
        final int[] index = {0};

        assetPositions.fields().forEachRemaining(entry -> {
            String symbol = entry.getKey();
            JsonNode asset = entry.getValue();
            String size = asset.get("size").asText();
            assets[index[0]++] = new AssetInfo(symbol, size);
        });

        return assets;
    }

    /**
     * 资产信息。
     */
    public static class AssetInfo {
        public final String symbol;
        public final String size;

        public AssetInfo(String symbol, String size) {
            this.symbol = symbol;
            this.size = size;
        }
    }

    /**
     * 将字符串数量转换为 long（放大 1e8）。
     * 例如："1499998" -> 1499998_00000000L
     */
    public static long parseSizeToLong(String sizeStr) {
        try {
            BigDecimal size = new BigDecimal(sizeStr);
            return size.multiply(BigDecimal.valueOf(1_0000_0000L)).longValue();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的数量格式: " + sizeStr, e);
        }
    }
    
    /**
     * 获取账户指定资产的余额。
     * 
     * @param address 账户地址
     * @param subaccountNumber 子账户编号
     * @param assetSymbol 资产符号（如 "USDT", "BTC", "ETH"）
     * @return 余额（放大 1e8），如果资产不存在返回 0
     * @throws IOException 网络或解析错误
     * @throws InterruptedException 中断异常
     */
    public long getAssetBalance(String address, int subaccountNumber, String assetSymbol) 
            throws IOException, InterruptedException {
        String json = getSubaccount(address, subaccountNumber);
        AssetInfo[] assets = parseAssetPositions(json);
        
        // 查找指定资产
        for (AssetInfo asset : assets) {
            if (assetSymbol.equals(asset.symbol)) {
                return parseSizeToLong(asset.size);
            }
        }
        
        return 0L; // 资产不存在，返回 0
    }
}

