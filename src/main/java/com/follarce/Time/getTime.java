package com.follarce.Time;

import java.time.*;

public class getTime {
    public static int[] getTime() {

        LocalDateTime now = LocalDateTime.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        int day = now.getDayOfMonth();
        int hour = now.getHour();
        int minute = now.getMinute();
        int second = now.getSecond();
        int nano = now.getNano();
        int millis = nano / 1_000_000;
        return new int[] { year, month, day, hour, minute, second, millis };
    }
}
