package com.grehful.restockalert;

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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

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

    static final String ACTION_STOP = "stop";
    static final String ACTION_STOP_ALARM = "stop_alarm";

    static final int DEFAULT_INTERVAL = 60;
    static final int MIN_INTERVAL = 20;

    private static final String CH_RUNNING = "running";
    private static final String CH_ALERT = "restock_alert";
    private static final int ID_RUNNING = 1;
    private static final int ID_LOGIN = 2;
    private static final int ALARM_SECONDS = 60;
    private static final int MAX_LOG_LINES = 40;

    private static MediaPlayer alarm;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private PageLoader pages;
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
        boolean loginNotified = false;
        Random random = new Random();
        int backoff = 0;

        while (running) {
            List<String> urls = urls(prefs);
            int interval = Math.max(prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL), MIN_INTERVAL);
            StringBuilder status = new StringBuilder();
            status.append("마지막 확인 ").append(now()).append('\n');
            boolean limited = false;

            for (String url : urls) {
                if (!running) break;
                String shortName = "상품 " + StockChecker.productNo(url);
                try {
                    StockChecker.Result r = StockChecker.parsePage(url, loader.load(url));
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
                    limited = true;
                    status.append("• ").append(shortName).append(": 사이트가 접속 제한 (").append(e.getMessage())
                            .append(")\n");
                } catch (Exception e) {
                    status.append("• ").append(shortName).append(": 확인 실패 (").append(e.getMessage())
                            .append(")\n");
                }
                if (urls.size() > 1) sleep(2000 + random.nextInt(2000));
            }

            if (limited) {
                backoff = Math.min(Math.max(backoff * 2, 60), 900);
                status.append("→ ").append(backoff).append("초 쉬었다가 다시 확인\n");
                appendLog(prefs, "접속 제한됨, " + backoff + "초 대기");
            } else {
                backoff = 0;
            }
            prefs.edit().putString(KEY_STATUS, status.toString().trim()).apply();
            updateRunningNotification(urls.size() + "개 상품 감시 중 · 마지막 확인 " + now());

            // 일정한 간격으로 두드리면 봇처럼 보이므로 약간의 무작위성을 준다.
            long wait = backoff > 0 ? backoff * 1000L : interval * 1000L + random.nextInt(interval * 300);
            sleep(wait);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdown() {
        running = false;
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
