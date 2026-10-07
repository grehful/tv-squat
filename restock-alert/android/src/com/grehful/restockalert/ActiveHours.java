package com.grehful.restockalert;

/** "start시부터 end시까지" 안에 hour 가 들어가는지. 자정을 넘기는 범위(예: 22시~2시)도 된다. */
final class ActiveHours {
    private ActiveHours() {}

    static boolean contains(int start, int end, int hour) {
        if (start == end) return true;
        if (start < end) return hour >= start && hour < end;
        return hour >= start || hour < end;
    }
}
