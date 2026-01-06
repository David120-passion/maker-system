package com.xinyue.maker.core.gateway;


import com.xinyue.maker.common.OrderCommand;
import com.xinyue.maker.common.TransferCommand;

public interface ExecutionGateway {
    /**
     * 发送订单请求
     * 要求：必须是 Non-blocking (非阻塞) 的，耗时 < 5us
     */
    void sendOrder(OrderCommand cmd);
    
    /**
     * 发送资产转移请求
     * 要求：必须是 Non-blocking (非阻塞) 的
     */
    void transfer(TransferCommand cmd);
}