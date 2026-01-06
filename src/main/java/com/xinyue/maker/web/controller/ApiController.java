package com.xinyue.maker.web.controller;

import org.noear.solon.SolonApp;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * API 控制器示例。
 */
@Controller
@Mapping("/api")
public class ApiController {

    /**
     * 获取系统状态。
     * GET /api/status
     */
    @Get
    @Mapping("/status")
    public Map<String, Object> status(Context context) {
        String s = context.realIp();
        String s1 = context.remoteIp();
        System.out.println(s1);
        System.out.println(s);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "OK");
        result.put("data", "系统运行正常");
        return result;
    }

    /**
     * Echo 接口：接收 JSON 请求体并返回。
     * POST /api/echo
     */
    @Post
    @Mapping("/echo")
    public Map<String, Object> echo(@Body Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "Echo success");
        result.put("data", body);
        return result;
    }

    /**
     * Hello 接口：接收查询参数。
     * GET /api/hello?name=xxx
     */
    @Get
    @Mapping("/hello")
    public Map<String, Object> hello(@Param String name) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "Hello, " + (name != null ? name : "World"));
        return result;
    }
}

