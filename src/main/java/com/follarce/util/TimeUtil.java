package com.follarce.util;

import java.time.*;

/**
 * 时间获取工具类
 * 提供获取当前时间组件的静态方法
 */
public class TimeUtil {
    /**
     * 获取当前时间的各个组件
     * @return 包含时间组件的整数数组，顺序为：年、月、日、时、分、秒、毫秒
     */
    public static int[] getTime() {
        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        // 提取各个时间组件
        int year = now.getYear();
        int month = now.getMonthValue();
        int day = now.getDayOfMonth();
        int hour = now.getHour();
        int minute = now.getMinute();
        int second = now.getSecond();
        int nano = now.getNano();
        // 将纳秒转换为毫秒
        int millis = nano / 1_000_000;
        // 返回时间组件数组
        return new int[] { year, month, day, hour, minute, second, millis };
    }
}
