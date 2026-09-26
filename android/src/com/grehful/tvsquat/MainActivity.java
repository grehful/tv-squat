package com.grehful.tvsquat;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 웹 화면(assets/www, PC 버전과 같은 코드)을 WebView로 띄우고,
 * 삼성 TV 제어와 음성 안내를 window.AndroidTv 로 넘겨준다.
 */
public class MainActivity extends Activity {
    // https 가상 주소로 assets를 제공해야 카메라(getUserMedia)와 wasm이 동작한다
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/index.html";
    private static final int CAMERA_REQUEST = 1;

    private WebView webView;
    private TvController tv;
    private TextToSpeech tts;
    private PermissionRequest pendingPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 운동하는 동안 화면이 꺼지지 않게
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tv = new TvController(this);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.KOREAN);
        });

        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect 로 화면 디버깅 가능 (시험용)
        webView = new WebView(this);
        webView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        webView.addJavascriptInterface(new Bridge(), "AndroidTv");
        webView.setWebViewClient(new AssetClient(getAssets()));
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handlePermission(request));
            }
        });
        setContentView(webView);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
        webView.loadUrl(START_URL);
    }

    private void handlePermission(PermissionRequest request) {
        if (!Uri.parse(START_URL).getHost().equals(request.getOrigin().getHost())) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else {
            pendingPermission = request;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != CAMERA_REQUEST) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingPermission != null) {
            if (granted) pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingPermission.deny();
            pendingPermission = null;
        } else if (granted) {
            webView.reload(); // 권한을 나중에 받았으면 카메라를 다시 시작
        }
    }

    @Override
    protected void onDestroy() {
        tv.shutdown();
        tts.shutdown();
        webView.destroy();
        super.onDestroy();
    }

    /** 자바스크립트에서 window.AndroidTv.xxx() 로 호출. 모두 바로 반환하고 결과는 status()로 확인. */
    private final class Bridge {
        @JavascriptInterface public void configure(String host, String mac) { tv.configure(host, mac); }
        @JavascriptInterface public String status() { return tv.status(); }
        @JavascriptInterface public void powerOff() { tv.powerOff(); }
        @JavascriptInterface public void powerOn() { tv.powerOn(); }
        @JavascriptInterface public void pair() { tv.pair(); }
        @JavascriptInterface public void discover() { tv.discover(); }

        @JavascriptInterface
        public void speak(String text) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tvsquat");
        }
    }

    /** https://appassets.androidplatform.net/... 요청을 assets/www 파일로 응답한다. */
    private static final class AssetClient extends WebViewClient {
        private static final Map<String, String> MIME = new HashMap<>();

        static {
            MIME.put("html", "text/html");
            MIME.put("js", "text/javascript");
            MIME.put("mjs", "text/javascript");
            MIME.put("css", "text/css");
            MIME.put("wasm", "application/wasm");
            MIME.put("json", "application/json");
            MIME.put("task", "application/octet-stream");
            MIME.put("png", "image/png");
            MIME.put("svg", "image/svg+xml");
        }

        private final AssetManager assets;

        AssetClient(AssetManager assets) { this.assets = assets; }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!ASSET_HOST.equals(url.getHost())) return null; // CDN 등 외부 주소는 그대로
            String path = url.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            if (path.contains("..")) return notFound();
            String ext = path.substring(path.lastIndexOf('.') + 1);
            String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
            try {
                InputStream in = assets.open("www" + path);
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-cache");
                headers.put("Access-Control-Allow-Origin", "*");
                return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, 200, "OK", headers, in);
            } catch (IOException e) {
                return notFound();
            }
        }

        private static WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }

        // 앱 밖 주소로 이동하지 않게 한다. (API 24 메서드라 android-23으로 빌드할 때는 @Override를 붙일 수 없음)
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !ASSET_HOST.equals(request.getUrl().getHost());
        }
    }
}
