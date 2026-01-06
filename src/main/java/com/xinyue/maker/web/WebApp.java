package com.xinyue.maker.web;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;

/**
 * Solon Web 服务器启动类。
 * 参考：https://solon.noear.org/article/55
 */
public class WebApp {
    
    /**
     * 程序入口。
     * 通过 Solon.start(...) 启动 Solon 的容器服务，进而启动它的所有机能。
     */
    public static void main(String[] args) {
        Solon.start(WebApp.class, args);
    }
}

