package com.xinyue.maker.web.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Get;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器。
 */
@Controller
public class HealthController {

    /**
     * 健康检查接口。
     * GET /health
     */
    @Get
    @Mapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * 系统信息接口。
     * GET /api/info
     */
    @Get
    @Mapping("/api/info")
    public Map<String, Object> info() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        Map<String, Object> result = new HashMap<>();
        
        Map<String, Object> jvm = new HashMap<>();
        jvm.put("version", System.getProperty("java.version"));
        jvm.put("totalMemory", totalMemory);
        jvm.put("freeMemory", freeMemory);
        jvm.put("usedMemory", usedMemory);
        jvm.put("maxMemory", maxMemory);

        Map<String, Object> system = new HashMap<>();
        system.put("processors", runtime.availableProcessors());
        system.put("uptime", System.currentTimeMillis() - 
                   java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());

        result.put("jvm", jvm);
        result.put("system", system);
        return result;
    }
}

