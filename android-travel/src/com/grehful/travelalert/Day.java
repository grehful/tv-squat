package com.grehful.travelalert;

/** 날짜를 1970-01-01 기준 일수(int)로 다루는 도구 (android-23 에 java.time 이 없어서 직접 구현). */
final class Day {
    private static final String WEEKDAYS = "목금토일월화수"; // 1970-01-01 = 목요일

    private Day() {}

    static int parse(String s) {
        String[] p = s.trim().split("-");
        if (p.length != 3) throw new IllegalArgumentException("날짜 형식은 YYYY-MM-DD: " + s);
        return of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    /** Howard Hinnant 의 days_from_civil. */
    static int of(int y, int m, int d) {
        if (m < 1 || m > 12 || d < 1 || d > 31) throw new IllegalArgumentException("잘못된 날짜: " + y + "-" + m + "-" + d);
        y -= m <= 2 ? 1 : 0;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** {년, 월, 일} */
    static int[] civil(int z) {
        z += 719468;
        int era = (z >= 0 ? z : z - 146096) / 146097;
        int doe = z - era * 146097;
        int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        int y = yoe + era * 400;
        int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        int mp = (5 * doy + 2) / 153;
        int d = doy - (153 * mp + 2) / 5 + 1;
        int m = mp + (mp < 10 ? 3 : -9);
        return new int[]{m <= 2 ? y + 1 : y, m, d};
    }

    static String iso(int day) {
        int[] c = civil(day);
        return String.format("%04d-%02d-%02d", c[0], c[1], c[2]);
    }

    static char weekday(int day) {
        return WEEKDAYS.charAt(((day % 7) + 7) % 7);
    }

    /** "2/5(금)" */
    static String label(int day) {
        int[] c = civil(day);
        return c[1] + "/" + c[2] + "(" + weekday(day) + ")";
    }

    /** 한국 시간 기준 오늘. */
    static int todayKst(long nowMillis) {
        return (int) ((nowMillis + 9 * 3600_000L) / 86_400_000L); // 1970년 이후만 다룸
    }
}
