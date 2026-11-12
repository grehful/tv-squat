package com.grehful.travelalert;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 화면은 assets/www (HTML) 이고, 저장·검색·예약은 window.Travel 로 넘겨준다. */
public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(false);
        webView.addJavascriptInterface(new Bridge(), "Travel");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:///android_asset/")) return false;
                openExternal(url);
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Runner.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.evaluateJavascript("window.onResumeApp && onResumeApp()", null);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.onBack && onBack()", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    private void js(String code) {
        runOnUiThread(() -> webView.evaluateJavascript(code, null));
    }

    private final class Bridge {
        @JavascriptInterface
        public String state() throws JSONException {
            SharedPreferences p = Runner.prefs(MainActivity.this);
            String key = p.getString("apiKey", "");
            JSONObject o = new JSONObject();
            o.put("config", Runner.config(MainActivity.this));
            o.put("hasKey", !key.isEmpty());
            o.put("keyHint", key.length() > 6 ? key.substring(0, 4) + "…" + key.substring(key.length() - 2) : "");
            o.put("hour", p.getInt("hour", 7));
            o.put("minute", p.getInt("minute", 0));
            o.put("alarmOn", p.getBoolean("alarmOn", true));
            o.put("next", p.getBoolean("alarmOn", true)
                    ? new SimpleDateFormat("M/d(E) a h:mm", Locale.KOREAN).format(new Date(Runner.nextAlarm(MainActivity.this)))
                    : JSONObject.NULL);
            o.put("last", p.getString("last", null) == null ? JSONObject.NULL : new JSONObject(p.getString("last", "{}")));
            o.put("lastError", p.getString("lastError", null) == null ? JSONObject.NULL : p.getString("lastError", ""));
            o.put("running", Runner.isRunning());
            return o.toString();
        }

        /** 설정 JSON 저장. 문제가 있으면 오류 문구, 성공이면 빈 문자열. */
        @JavascriptInterface
        public String saveConfig(String json) {
            try {
                Trip.parseAll(new JSONObject(json));
            } catch (JSONException e) {
                return e.getMessage();
            }
            Runner.prefs(MainActivity.this).edit().putString("config", json).apply();
            return "";
        }

        @JavascriptInterface
        public String defaultConfig() {
            Runner.prefs(MainActivity.this).edit().remove("config").apply();
            return Runner.config(MainActivity.this);
        }

        /** apiKey 가 null 이면 기존 키 유지, "" 이면 삭제(체험 모드). */
        @JavascriptInterface
        public void saveSettings(String apiKey, int hour, int minute, boolean alarmOn) {
            SharedPreferences.Editor e = Runner.prefs(MainActivity.this).edit()
                    .putInt("hour", hour).putInt("minute", minute).putBoolean("alarmOn", alarmOn);
            if (apiKey != null && !"null".equals(apiKey)) e.putString("apiKey", apiKey.trim());
            e.commit();
            Runner.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public void runNow(boolean withNotification) {
            if (Runner.isRunning()) return;
            new Thread(() -> {
                String err = null;
                try {
                    JSONObject r = Runner.search(MainActivity.this);
                    if (withNotification) {
                        String[] s = Engine.summary(r);
                        Runner.notify(MainActivity.this, s[0], s[1]);
                    }
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                js("onSearchDone(" + JSONObject.quote(err == null ? "" : err) + ")");
            }, "travel-search").start();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url.startsWith("https://")) openExternal(url);
        }

        @JavascriptInterface
        public void openBatterySettings() {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }
}
