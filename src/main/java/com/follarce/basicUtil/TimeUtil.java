package com.follarce.basicUtil;

import java.time.*;

/**
 * Time utility class
 * Provides static methods for getting current time components
 */
public class TimeUtil {
    /**
     * Get current time components
     * @return Integer array containing time components in order: Year, Month, Day, Hour, Minute, Second, Millisecond
     */
    public static int[] getTime() {
        // Get current time
        LocalDateTime now = LocalDateTime.now();
        // Extract time components
        int year = now.getYear();
        int month = now.getMonthValue();
        int day = now.getDayOfMonth();
        int hour = now.getHour();
        int minute = now.getMinute();
        int second = now.getSecond();
        int nano = now.getNano();
        // Convert nanoseconds to milliseconds
        int millis = nano / Constants.NANOS_TO_MILLIS;
        // Return time component array
        return new int[] { year, month, day, hour, minute, second, millis };
    }

    /**
     * Get current time array (for metadata)
     * @return Time array [Year, Month, Day, Hour, Minute, Second, Millisecond]
     */
    public static int[] getTimeArray() {
        return getTime();
    }
}
