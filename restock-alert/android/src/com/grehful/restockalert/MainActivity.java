package com.grehful.restockalert;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 설정 화면. 실제 확인은 MonitorService 가 한다. */
public class MainActivity extends Activity {
    static final String EXTRA_OPEN_URL = "open_url";
    private static final String DEFAULT_URL =
            "https://m.smartstore.naver.com/moncolle_korea/products/13763561200\n"
            + "https://m.ssg.com/item/itemView.ssg?itemId=1000881834004&siteNo=6004&salestrNo=6005";

    private SharedPreferences prefs;
    private EditText urlsInput;
    private EditText intervalInput;
    private CheckBox alarmBox;
    private CheckBox hoursBox;
    private EditText startHourInput;
    private EditText endHourInput;
    private Button startStop;
    private Button batteryButton;
    private TextView statusView;
    private TextView logView;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE);
        MonitorService.createChannels(this);
        buildUi();
        loadSettings();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        handleOpenUrl(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleOpenUrl(intent);
    }

    /** 입고 알림을 누르면 알람을 끄고 바로 구매 페이지를 연다. */
    private void handleOpenUrl(Intent intent) {
        String url = intent.getStringExtra(EXTRA_OPEN_URL);
        if (url == null) return;
        intent.removeExtra(EXTRA_OPEN_URL);
        MonitorService.stopAlarm();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "페이지를 열 앱이 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        refresh();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        super.onPause();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("스마트스토어 재입고 알림");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        root.addView(label("상품 주소 (스마트스토어·SSG.COM, 여러 개면 한 줄에 하나씩)"));
        urlsInput = new EditText(this);
        urlsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        urlsInput.setMinLines(2);
        urlsInput.setTextSize(13);
        root.addView(urlsInput);

        root.addView(label("확인 간격 (초, 최소 " + MonitorService.MIN_INTERVAL + ")"));
        intervalInput = new EditText(this);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(intervalInput);

        hoursBox = new CheckBox(this);
        hoursBox.setText("정해진 시간에만 확인 (밤에는 쉬기)");
        root.addView(hoursBox);
        LinearLayout hoursRow = new LinearLayout(this);
        hoursRow.setOrientation(LinearLayout.HORIZONTAL);
        startHourInput = hourInput();
        endHourInput = hourInput();
        hoursRow.addView(startHourInput);
        hoursRow.addView(plain("시부터  "));
        hoursRow.addView(endHourInput);
        hoursRow.addView(plain("시까지 (0~23)"));
        root.addView(hoursRow);

        alarmBox = new CheckBox(this);
        alarmBox.setText("입고되면 알람 소리 울리기 (무음 모드에서도)");
        root.addView(alarmBox);

        startStop = new Button(this);
        startStop.setTextSize(18);
        startStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        root.addView(startStop);

        Button test = new Button(this);
        test.setText("알림 테스트");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                MonitorService.alert(MainActivity.this, prefs, "테스트 상품",
                        "알림 테스트입니다. 입고되면 이렇게 알려드려요.", firstUrl());
            }
        });
        root.addView(test);

        Button login = new Button(this);
        login.setText("네이버 로그인");
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, LoginActivity.class);
                for (String u : MonitorService.urls(prefs)) {
                    if (!StockChecker.isSsg(u)) {
                        i.putExtra(LoginActivity.EXTRA_RETURN_URL, u);
                        break;
                    }
                }
                startActivity(i);
            }
        });
        root.addView(login);

        batteryButton = new Button(this);
        batteryButton.setText("배터리 최적화 끄기 (권장)");
        batteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBatteryExemption();
            }
        });
        root.addView(batteryButton);

        root.addView(label("현재 상태"));
        statusView = new TextView(this);
        statusView.setTextIsSelectable(true);
        root.addView(statusView);

        root.addView(label("기록"));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView);

        TextView note = new TextView(this);
        note.setText("\n입고 알림을 누르면 구매 페이지가 열립니다. 결제는 직접 하세요.\n"
                + "네이버페이에 결제수단과 배송지를 미리 등록해 두면 훨씬 빨라요.");
        note.setTextSize(12);
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setFitsSystemWindows(true);
        setContentView(scroll);
    }

    private EditText hourInput() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setEms(2);
        e.setGravity(android.view.Gravity.CENTER);
        return e;
    }

    private TextView plain(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        return t;
    }

    private static int parseHour(EditText e, int fallback) {
        try {
            int h = Integer.parseInt(e.getText().toString().trim());
            return h >= 0 && h <= 23 ? h : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void loadSettings() {
        urlsInput.setText(prefs.getString(MonitorService.KEY_URLS, DEFAULT_URL));
        intervalInput.setText(String.valueOf(prefs.getInt(MonitorService.KEY_INTERVAL, MonitorService.DEFAULT_INTERVAL)));
        hoursBox.setChecked(prefs.getBoolean(MonitorService.KEY_HOURS_ON, false));
        startHourInput.setText(String.valueOf(prefs.getInt(MonitorService.KEY_START_HOUR, MonitorService.DEFAULT_START_HOUR)));
        endHourInput.setText(String.valueOf(prefs.getInt(MonitorService.KEY_END_HOUR, MonitorService.DEFAULT_END_HOUR)));
        alarmBox.setChecked(prefs.getBoolean(MonitorService.KEY_ALARM, true));
        alarmBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(MonitorService.KEY_ALARM, checked).apply();
            }
        });
    }

    private void saveSettings() {
        int interval;
        try {
            interval = Integer.parseInt(intervalInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            interval = MonitorService.DEFAULT_INTERVAL;
        }
        interval = Math.max(interval, MonitorService.MIN_INTERVAL);
        intervalInput.setText(String.valueOf(interval));
        int startHour = parseHour(startHourInput, MonitorService.DEFAULT_START_HOUR);
        int endHour = parseHour(endHourInput, MonitorService.DEFAULT_END_HOUR);
        startHourInput.setText(String.valueOf(startHour));
        endHourInput.setText(String.valueOf(endHour));
        prefs.edit()
                .putString(MonitorService.KEY_URLS, urlsInput.getText().toString().trim())
                .putInt(MonitorService.KEY_INTERVAL, interval)
                .putBoolean(MonitorService.KEY_ALARM, alarmBox.isChecked())
                .putBoolean(MonitorService.KEY_HOURS_ON, hoursBox.isChecked())
                .putInt(MonitorService.KEY_START_HOUR, startHour)
                .putInt(MonitorService.KEY_END_HOUR, endHour)
                .apply();
    }

    private String firstUrl() {
        java.util.List<String> u = MonitorService.urls(prefs);
        return u.isEmpty() ? null : u.get(0);
    }

    private void toggle() {
        if (prefs.getBoolean(MonitorService.KEY_RUNNING, false)) {
            MonitorService.stop(this);
            prefs.edit().putBoolean(MonitorService.KEY_RUNNING, false).apply();
        } else {
            saveSettings();
            if (MonitorService.urls(prefs).isEmpty()) {
                Toast.makeText(this, "상품 주소를 확인해 주세요 (스마트스토어 또는 SSG.COM 상품 주소)", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString(MonitorService.KEY_STATUS, "첫 확인 중…").apply();
            MonitorService.start(this);
        }
        refresh();
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refresh() {
        boolean running = prefs.getBoolean(MonitorService.KEY_RUNNING, false);
        startStop.setText(running ? "■ 감시 중지" : "▶ 감시 시작");
        urlsInput.setEnabled(!running);
        intervalInput.setEnabled(!running);
        hoursBox.setEnabled(!running);
        startHourInput.setEnabled(!running);
        endHourInput.setEnabled(!running);
        statusView.setText(running ? prefs.getString(MonitorService.KEY_STATUS, "") : "멈춤");
        logView.setText(prefs.getString(MonitorService.KEY_LOG, ""));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryButton.setVisibility(exempt ? View.GONE : View.VISIBLE);
    }
}
