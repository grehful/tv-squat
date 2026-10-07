package com.grehful.restockalert;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 화면이 꺼져 있어도 상품 페이지를 주기적으로 확인하고,
 * 품절 -> 판매중으로 바뀌면 알림 + 알람 소리를 낸다. 자동 구매는 하지 않는다.
 */
public class MonitorService extends Service {
    static final String PREFS = "restock";
    static final String KEY_URLS = "urls";
    static final String KEY_INTERVAL = "interval";
    static final String KEY_ALARM = "alarm";
    static final String KEY_RUNNING = "running";
    static final String KEY_STATUS = "status";
    static final String KEY_LOG = "log";
    static final String KEY_HOURS_ON = "hours_on";
    static final String KEY_START_HOUR = "start_hour";
    static final String KEY_END_HOUR = "end_hour";
    static final int DEFAULT_START_HOUR = 9;
    static final int DEFAULT_END_HOUR = 22;
    /** 한 사이트가 이만큼 연달아 막으면 (쉬는 시간 합계 약 1시간 반) 그 사이트 확인을 멈춘다. */
    private static final int MAX_LIMITED_ROUNDS = 5;

    static final String ACTION_STOP = "stop";
    static final String ACTION_STOP_ALARM = "stop_alarm";
    private static final String ACTION_WAKE = "wake";

    static final int DEFAULT_INTERVAL = 60;
    static final int MIN_INTERVAL = 20;

    private static final String CH_RUNNING = "running";
    private static final String CH_ALERT = "restock_alert";
    private static final int ID_RUNNING = 1;
    private static final int ID_LOGIN = 2;
    private static final int ID_STOPPED = 3;
    private static final int ALARM_SECONDS = 60;
    private static final int MAX_LOG_LINES = 40;

    private static MediaPlayer alarm;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private PageLoader pages;
    private final Object waiter = new Object();
    private volatile boolean running;

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, MonitorService.class));
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, MonitorService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_ALARM.equals(action)) {
            stopAlarm();
            if (!running) stopSelf();
            return running ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            appendLog(prefs, "감시 중지");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_WAKE.equals(action) && running) {
            wakeWorker(); // 쉬는 시간이 끝났다
            return START_STICKY;
        }
        // 시작 요청, 또는 시스템이 서비스를 다시 살린 경우 (intent == null)
        if (intent == null && !prefs.getBoolean(KEY_RUNNING, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        Notification n = runningNotification("확인 준비 중");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_RUNNING, n);
        }
        if (!running) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "restockalert:monitor");
            wakeLock.acquire();
            pages = new PageLoader(this);
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "restock-monitor");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        final PageLoader loader = pages;
        appendLog(prefs, "감시 시작");
        Map<String, Boolean> lastInStock = new HashMap<>();
        // 사이트별로 따로 쉰다: 한 곳이 막아도 다른 사이트는 계속 확인한다
        Map<String, Integer> siteBackoff = new HashMap<>();
        Map<String, Long> siteResumeAt = new HashMap<>();
        Map<String, Integer> siteLimitedCount = new HashMap<>();
        Set<String> givenUp = new HashSet<>();
        boolean loginNotified = false;
        Random random = new Random();

        while (running) {
            if (!inActiveHours(prefs, Calendar.getInstance())) {
                restUntilActiveHours();
                continue;
            }
            List<String> urls = urls(prefs);
            int interval = Math.max(prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL), MIN_INTERVAL);
            StringBuilder status = new StringBuilder();
            status.append("마지막 확인 ").append(now()).append('\n');
            boolean checkedAny = false;

            for (String url : urls) {
                if (!running) break;
                String site = siteName(url);
                String shortName = "상품 " + StockChecker.productNo(url);
                if (givenUp.contains(site)) {
                    status.append("• ").append(shortName).append(": ").append(site)
                            .append(" 확인 멈춤 (계속 접속 제한)\n");
                    continue;
                }
                Long resumeAt = siteResumeAt.get(site);
                if (resumeAt != null && System.currentTimeMillis() < resumeAt) {
                    long min = Math.max(1, (resumeAt - System.currentTimeMillis() + 59999) / 60000);
                    status.append("• ").append(shortName).append(": ").append(site)
                            .append(" 접속 제한으로 쉬는 중 (약 ").append(min).append("분 뒤 다시 확인)\n");
                    continue;
                }
                checkedAny = true;
                try {
                    StockChecker.Result r = StockChecker.parsePage(url, loader.load(url));
                    siteBackoff.remove(site);
                    siteResumeAt.remove(site);
                    siteLimitedCount.remove(site);
                    String name = r.name != null ? r.name : shortName;
                    status.append("• ").append(name).append(": ").append(r.description).append('\n');
                    if (r.loginRequired && !loginNotified) {
                        loginNotified = true;
                        appendLog(prefs, "네이버 로그인 필요");
                        notifyLoginNeeded(url);
                    } else if (!StockChecker.isSsg(url) && r.inStock != null) {
                        loginNotified = false; // 로그인이 다시 풀리면 또 알려준다
                    }
                    Boolean before = lastInStock.get(url);
                    if (Boolean.TRUE.equals(r.inStock) && !Boolean.TRUE.equals(before)) {
                        appendLog(prefs, "입고! " + name);
                        alert(this, prefs, name, "지금 구매 가능: " + r.description, url);
                    }
                    if (r.inStock != null) {
                        if (before != null && !before.equals(r.inStock)) {
                            appendLog(prefs, name + " → " + r.description);
                        }
                        lastInStock.put(url, r.inStock);
                    }
                } catch (StockChecker.RateLimitedException e) {
                    int count = siteLimitedCount.containsKey(site) ? siteLimitedCount.get(site) + 1 : 1;
                    siteLimitedCount.put(site, count);
                    if (count >= MAX_LIMITED_ROUNDS) {
                        givenUp.add(site);
                        appendLog(prefs, site + " 접속 제한이 계속돼서 " + site + " 확인 멈춤");
                        notifySiteGivenUp(site);
                    } else {
                        // 5분 → 10분 → 20분 → 30분
                        int prev = siteBackoff.containsKey(site) ? siteBackoff.get(site) : 0;
                        int backoff = Math.min(Math.max(prev * 2, 300), 1800);
                        siteBackoff.put(site, backoff);
                        siteResumeAt.put(site, System.currentTimeMillis() + backoff * 1000L);
                        appendLog(prefs, site + " 접속 제한 (" + e.getMessage() + "), " + site + "만 "
                                + (backoff / 60) + "분 쉬기");
                    }
                    status.append("• ").append(shortName).append(": ").append(site).append("가 접속 제한 (")
                            .append(e.getMessage()).append(")\n");
                } catch (Exception e) {
                    status.append("• ").append(shortName).append(": 확인 실패 (").append(e.getMessage())
                            .append(")\n");
                }
                if (urls.size() > 1) sleep(2000 + random.nextInt(2000));
            }

            if (!urls.isEmpty() && allGivenUp(urls, givenUp)) {
                prefs.edit().putString(KEY_STATUS, status.toString().trim()).apply();
                stopBecauseBlocked();
                return;
            }
            prefs.edit().putString(KEY_STATUS, status.toString().trim()).apply();
            if (checkedAny) updateRunningNotification(urls.size() + "개 상품 감시 중 · 마지막 확인 " + now());

            // 일정한 간격으로 두드리면 봇처럼 보이므로 약간의 무작위성을 준다.
            sleep(interval * 1000L + random.nextInt(interval * 300));
        }
    }

    static String siteName(String url) {
        return StockChecker.isSsg(url) ? "SSG" : "네이버";
    }

    private static boolean allGivenUp(List<String> urls, Set<String> givenUp) {
        for (String u : urls) {
            if (!givenUp.contains(siteName(u))) return false;
        }
        return true;
    }

    private void notifySiteGivenUp(String site) {
        PendingIntent open = PendingIntent.getActivity(this, 6, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(site + " 상품 확인을 멈췄어요")
                .setContentText(site + "가 계속 접속을 막아서 잠시 멈췄어요. 다른 사이트는 계속 확인해요.")
                .setStyle(new Notification.BigTextStyle().bigText(site + "가 1시간 넘게 계속 접속을 막아서 "
                        + site + " 상품 확인만 멈췄어요. 다른 사이트는 계속 확인해요. "
                        + "몇 시간 뒤 감시를 다시 시작하면 " + site + "도 다시 확인해요."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_STOPPED + 10 + Math.abs(site.hashCode() % 10), n);
    }

    /** 기다리는 중에 감시 중지나 쉬는 시간 끝 신호가 오면 바로 깬다. */
    private void sleep(long ms) {
        synchronized (waiter) {
            if (!running) return;
            try {
                waiter.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wakeWorker() {
        synchronized (waiter) {
            waiter.notifyAll();
        }
    }

    static boolean inActiveHours(SharedPreferences prefs, Calendar now) {
        if (!prefs.getBoolean(KEY_HOURS_ON, false)) return true;
        int start = prefs.getInt(KEY_START_HOUR, DEFAULT_START_HOUR);
        int end = prefs.getInt(KEY_END_HOUR, DEFAULT_END_HOUR);
        return ActiveHours.contains(start, end, now.get(Calendar.HOUR_OF_DAY));
    }

    /**
     * 쉬는 시간에는 휴대폰이 잠들 수 있게 wake lock 을 풀고,
     * 다시 시작할 시각에 AlarmManager 로 깨운다.
     */
    private void restUntilActiveHours() {
        int start = prefs.getInt(KEY_START_HOUR, DEFAULT_START_HOUR);
        int end = prefs.getInt(KEY_END_HOUR, DEFAULT_END_HOUR);
        Calendar next = Calendar.getInstance();
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        next.set(Calendar.HOUR_OF_DAY, start);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_MONTH, 1);

        String until = String.format(Locale.KOREA, "%d시", start);
        String msg = "쉬는 시간 (" + start + "시~" + end + "시에만 확인) · " + until + "에 다시 시작";
        prefs.edit().putString(KEY_STATUS, msg).apply();
        updateRunningNotification(msg);
        appendLog(prefs, "쉬는 시간, " + until + "에 다시 시작");

        PendingIntent wake = PendingIntent.getService(this, 4,
                new Intent(this, MonitorService.class).setAction(ACTION_WAKE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = getSystemService(AlarmManager.class);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), wake);

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // 알람이 늦거나 빠져도 10분마다 시계를 다시 본다
        while (running && !inActiveHours(prefs, Calendar.getInstance())) {
            sleep(10 * 60 * 1000L);
        }
        am.cancel(wake);
        if (running && wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        if (running) appendLog(prefs, "쉬는 시간 끝, 다시 확인 시작");
    }

    /** 접속 제한이 계속되면 계정/접속이 더 막히기 전에 스스로 멈추고 알려준다. */
    private void stopBecauseBlocked() {
        prefs.edit().putBoolean(KEY_RUNNING, false).apply();
        appendLog(prefs, "접속 제한이 계속돼서 감시를 자동으로 멈춤");
        PendingIntent open = PendingIntent.getActivity(this, 5, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("재입고 감시를 멈췄어요")
                .setContentText("사이트가 계속 접속을 막고 있어요. 몇 시간 뒤에 다시 시작해 주세요.")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "모든 사이트가 계속 접속을 막고 있어서 감시를 멈췄어요. "
                                + "몇 시간 쉬었다가 확인 간격을 늘려서 다시 시작해 주세요."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_STOPPED, n);
        main.post(new Runnable() {
            @Override
            public void run() {
                shutdown();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    private void shutdown() {
        running = false;
        wakeWorker();
        if (worker != null) worker.interrupt();
        worker = null;
        if (pages != null) pages.destroy();
        pages = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- 알림 ----

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUNNING, "감시 중 표시", NotificationManager.IMPORTANCE_LOW);
        run.setShowBadge(false);
        nm.createNotificationChannel(run);

        NotificationChannel alert = new NotificationChannel(CH_ALERT, "입고 알림", NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alert.setBypassDnd(true);
        nm.createNotificationChannel(alert);
    }

    private Notification runningNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MonitorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_RUNNING)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("재입고 감시 중")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "감시 중지", stop).build())
                .build();
    }

    private void updateRunningNotification(String text) {
        if (!running) return;
        getSystemService(NotificationManager.class).notify(ID_RUNNING, runningNotification(text));
    }

    private void notifyLoginNeeded(String url) {
        PendingIntent login = PendingIntent.getActivity(this, 3,
                new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_RETURN_URL, url),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("네이버 로그인이 필요해요")
                .setContentText("눌러서 로그인하면 네이버 상품도 계속 확인합니다")
                .setContentIntent(login)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_LOGIN, n);
    }

    static void alert(Context c, SharedPreferences prefs, String name, String text, String url) {
        createChannels(c);
        Intent openIntent = new Intent(c, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int id = 1000 + (url == null ? 0 : Math.abs(url.hashCode() % 100000));
        PendingIntent open = PendingIntent.getActivity(c, id, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopAlarm = PendingIntent.getService(c, 2,
                new Intent(c, MonitorService.class).setAction(ACTION_STOP_ALARM),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("입고! " + name)
                .setContentText(text + " · 눌러서 구매 페이지 열기")
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n눌러서 구매 페이지 열기"))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setDeleteIntent(stopAlarm)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "구매 페이지 열기", open).build())
                .addAction(new Notification.Action.Builder(null, "알람 끄기", stopAlarm).build())
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);

        if (prefs.getBoolean(KEY_ALARM, true)) {
            final Context app = c.getApplicationContext();
            main.post(new Runnable() {
                @Override
                public void run() {
                    startAlarm(app);
                }
            });
        }
    }

    /** 무음 모드여도 들리도록 알람 소리로 1분간 울린다. */
    private static void startAlarm(Context c) {
        STOP_ALARM.run(); // 이미 울리는 알람이 있으면 정리 (메인 스레드에서 바로 실행)
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            p.setDataSource(c, sound);
            p.setLooping(true);
            p.prepare();
            p.start();
            alarm = p;
            main.postDelayed(STOP_ALARM, ALARM_SECONDS * 1000L);
        } catch (Exception e) {
            appendLog(c.getSharedPreferences(PREFS, MODE_PRIVATE), "알람 소리 재생 실패: " + e.getMessage());
        }
    }

    private static final Runnable STOP_ALARM = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (alarm != null) {
                try {
                    alarm.stop();
                } catch (IllegalStateException ignored) {
                }
                alarm.release();
                alarm = null;
            }
        }
    };

    static void stopAlarm() {
        main.post(STOP_ALARM);
    }

    // ---- 설정 ----

    static List<String> urls(SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        for (String line : prefs.getString(KEY_URLS, "").split("\\s+")) {
            String u = line.trim();
            if (u.startsWith("http") && StockChecker.productNo(u) != null) out.add(u);
        }
        return out;
    }

    static synchronized void appendLog(SharedPreferences prefs, String line) {
        String old = prefs.getString(KEY_LOG, "");
        String[] lines = (now() + "  " + line + "\n" + old).split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, MAX_LOG_LINES); i++) sb.append(lines[i]).append('\n');
        prefs.edit().putString(KEY_LOG, sb.toString()).apply();
    }

    private static String now() {
        return new SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA).format(new Date());
    }
}
