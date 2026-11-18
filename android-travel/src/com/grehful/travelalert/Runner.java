package com.grehful.travelalert;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Calendar;

/** 설정 저장, 검색 실행, 매일 아침 예약, 알림 표시. */
final class Runner {
    static final String PREFS = "travel";
    private static final String CHANNEL = "daily";
    static final int JOB_ID = 1;
    static final int RETRY_JOB_ID = 2;
    static final int MAX_RETRIES_PER_DAY = 4;
    static final long RETRY_DELAY_MS = 10 * 60_000L;
    private static final int FLAG_IMMUTABLE = 0x04000000; // PendingIntent.FLAG_IMMUTABLE (API 23)
    private static volatile boolean running;

    private Runner() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String config(Context c) {
        String s = prefs(c).getString("config", null);
        if (s != null) return s;
        try {
            return Prices.read(c.getAssets().open("default-travel.json"));
        } catch (IOException e) {
            return "{\"origin\":\"ICN\",\"trips\":[]}";
        }
    }

    static boolean isRunning() { return running; }

    /** 검색하고 결과를 저장한다. 키가 없으면 체험 모드(가짜 가격, 기록 안 함). */
    static JSONObject search(Context c) throws Exception {
        return search(c, false);
    }

    /** resume: 오늘 인터넷 문제로 놓친 날짜만 (남은 검색 횟수 안에서) 다시 검색. */
    static synchronized JSONObject search(Context c, boolean resume) throws Exception {
        // 다른 곳(아침 작업/버튼)에서 이미 검색 중이면 기다렸다가 이어서 실행된다
        running = true;
        try {
            SharedPreferences p = prefs(c);
            JSONObject root = new JSONObject(config(c));
            String key = p.getString("apiKey", "").trim();
            int today = Day.todayKst(System.currentTimeMillis());
            boolean demo = key.isEmpty();
            Prices.Client client = demo ? new DemoClient(today)
                    : new Prices.SerpApi(key, root.optString("currency", "KRW"), p.getString("hiltonBrands", null));
            JSONObject history = demo ? new JSONObject() : new JSONObject(p.getString("history", "{}"));
            JSONObject combos = demo ? new JSONObject() : new JSONObject(p.getString("combos", "{}"));
            java.util.List<Trip> trips = Trip.parseAll(root);
            if (demo) for (Trip t : trips) t.maxSearches = 0; // 체험 모드는 공짜라 매번 모든 조합
            JSONObject result = Engine.run(client, trips, today, history, combos, resume && !demo);
            result.put("demo", demo);
            result.put("at", System.currentTimeMillis());
            SharedPreferences.Editor e = p.edit().putString("last", result.toString()).remove("lastError");
            if (!demo) e.putString("history", history.toString()).putString("combos", combos.toString());
            if (client instanceof Prices.SerpApi && ((Prices.SerpApi) client).hiltonBrands != null) {
                e.putString("hiltonBrands", ((Prices.SerpApi) client).hiltonBrands);
            }
            e.apply();
            if (!demo) scheduleRetryIfNeeded(c, result, today);
            return result;
        } catch (Exception | Error ex) {
            prefs(c).edit().putString("lastError", ex.getMessage() == null ? ex.toString() : ex.getMessage()).apply();
            throw ex;
        } finally {
            running = false;
        }
    }

    /** 인터넷 문제로 놓친 날짜가 있으면 10분 뒤(인터넷 연결 시) 그 날짜만 다시 검색하도록 맡긴다. */
    static void scheduleRetryIfNeeded(Context c, JSONObject result, int today) {
        SharedPreferences p = prefs(c);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (result.optInt("networkMissed") == 0) {
            js.cancel(RETRY_JOB_ID);
            return;
        }
        int count = p.getInt("retryDay", -1) == today ? p.getInt("retryCount", 0) : 0;
        if (count >= MAX_RETRIES_PER_DAY) return;
        p.edit().putInt("retryDay", today).putInt("retryCount", count + 1).apply();
        js.schedule(new JobInfo.Builder(RETRY_JOB_ID, new ComponentName(c, SearchJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(RETRY_DELAY_MS * (count + 1))
                .setOverrideDeadline(RETRY_DELAY_MS * (count + 1) + 3 * 3600_000L)
                .build());
        try {
            result.put("retryScheduled", true);
            p.edit().putString("last", result.toString()).apply();
        } catch (org.json.JSONException ignored) {
        }
    }

    // ---- 매일 아침 예약 ----

    static long nextAlarm(Context c) {
        SharedPreferences p = prefs(c);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, p.getInt("hour", 7));
        cal.set(Calendar.MINUTE, p.getInt("minute", 0));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 30_000) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(c);
        am.cancel(pi);
        if (!prefs(c).getBoolean("alarmOn", true)) return;
        // 정확한 알람 권한 없이도 잠자기 모드에서 울리는 방식 (몇 분 늦을 수 있음)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAlarm(c), pi);
    }

    /** 인터넷이 연결되면 바로 검색하도록 작업을 맡긴다 (최대 10분 실행 가능). */
    static void enqueueJob(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, SearchJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(3 * 3600_000L)
                .build());
    }

    // ---- 알림 ----

    private static void ensureChannel(NotificationManager nm) {
        try {
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "매일 가격 알림", 3 /* IMPORTANCE_DEFAULT */);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
        } catch (ReflectiveOperationException ignored) {
            // 안드로이드 8 미만에는 채널이 없다
        }
    }

    static final String KAKAO = "com.kakao.talk";

    static Intent shareIntent(String text) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
    }

    /** 카카오톡 공유 화면을 바로 연다. 카톡이 없으면 공유할 앱 고르기 화면. */
    static void share(Context c, String text) {
        Intent kakao = shareIntent(text).setPackage(KAKAO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(kakao);
        } catch (android.content.ActivityNotFoundException e) {
            c.startActivity(Intent.createChooser(shareIntent(text), "공유하기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    static void notify(Context c, String title, String body) {
        notify(c, title, body, false);
    }

    @SuppressWarnings("deprecation")
    static void notify(Context c, String title, String body, boolean quiet) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(nm);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body.split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(quiet);
        // 알림에서 바로 카톡 공유 (공유할 앱 고르기 → 카카오톡)
        Intent chooser = Intent.createChooser(shareIntent(title + "\n\n" + body), "공유하기");
        PendingIntent sharePi = PendingIntent.getActivity(c, 2, chooser, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "💬 공유", sharePi).build());
        try {
            Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, CHANNEL);
        } catch (ReflectiveOperationException ignored) {
        }
        nm.notify(1, b.build());
    }
}
