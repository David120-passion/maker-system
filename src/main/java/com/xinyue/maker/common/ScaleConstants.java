package com.xinyue.maker.common;

/**
 * 价格和数量的精度缩放常量。
 * <p>
 * 系统中所有价格和数量都使用 long 类型存储，通过固定缩放因子（1e8）来保证精度。
 */
public final class ScaleConstants {

    /** 价格和数量的精度缩放因子（1e8，即 100,000,000） */
    public static final long SCALE_E8 = 100_000_000L;

    private ScaleConstants() {
        // 工具类，禁止实例化
    }
}
