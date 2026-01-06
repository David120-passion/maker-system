package com.xinyue.maker.io.output;

import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.core.gateway.ExecutionGateway;
import org.agrona.collections.Object2ObjectHashMap;

/**
 * 管理多个交易所的 ExecutionGateway 实例。
 * 根据 Exchange 路由到对应的网关。
 */
public final class ExecutionGatewayManager {

    private final Object2ObjectHashMap<Exchange, ExecutionGateway> gateways = new Object2ObjectHashMap<>();

    /**
     * 注册交易所的 ExecutionGateway。
     */
    public ExecutionGatewayManager register(Exchange exchange, ExecutionGateway gateway) {
        gateways.put(exchange, gateway);
        return this;
    }

    /**
     * 获取指定交易所的 ExecutionGateway。
     */
    public ExecutionGateway getGateway(Exchange exchange) {
        return gateways.get(exchange);
    }

    /**
     * 启动所有网关的连接。
     */
    public void startAll() {
        // 各个网关的连接启动逻辑由各自的 Connector 负责
        // 这里可以添加统一的启动逻辑，如果需要的话
    }

    /**
     * 停止所有网关的连接。
     */
    public void stopAll() {
        // 各个网关的连接停止逻辑由各自的 Connector 负责
        // 这里可以添加统一的停止逻辑，如果需要的话
    }
}

