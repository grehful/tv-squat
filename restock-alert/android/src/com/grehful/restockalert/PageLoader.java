package com.grehful.restockalert;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 화면에 보이지 않는 WebView(휴대폰의 크롬 엔진)로 상품 페이지를 연다.
 * 네이버와 SSG는 일반 HTTP 요청을 봇으로 보고 429/403 으로 막기 때문에,
 * 사람이 브라우저로 여는 것과 똑같이 열어서 페이지 내용을 가져온다.
 */
final class PageLoader {
    private static final long LOAD_TIMEOUT_MS = 30000;
    /** 페이지가 다 열린 뒤 스크립트가 화면을 그릴 시간을 조금 준다. */
    private static final long SETTLE_MS = 1500;

    // 네이버 상태 객체가 있으면 페이지 맨 앞에 붙여서 StockChecker.parse 가 바로 찾게 한다.
    private static final String EXTRACT_JS = "(function(){var s=window.__PRELOADED_STATE__;"
            + "var head='';try{if(s)head='<script>window.__PRELOADED_STATE__='+JSON.stringify(s)+'</script>';}catch(e){}"
            + "return head+document.documentElement.outerHTML;})()";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private WebView webView;

    // 한 번에 한 페이지만 연다. 아래 값들은 메인 스레드에서만 바뀐다.
    private CountDownLatch done;
    private String html;
    private int httpError;
    private String loadError;
    private boolean extracting;

    PageLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 작업 스레드에서 호출한다. 페이지 HTML 을 돌려준다. */
    synchronized String load(final String url) throws IOException {
        done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                html = null;
                httpError = 0;
                loadError = null;
                extracting = false;
                ensureWebView().loadUrl(url);
                // 끝났다는 신호가 안 와도 시간이 되면 있는 그대로 읽는다
                main.postDelayed(extractRunnable, LOAD_TIMEOUT_MS);
            }
        });
        try {
            if (!done.await(LOAD_TIMEOUT_MS + 10000, TimeUnit.MILLISECONDS)) {
                throw new IOException("페이지 열기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨");
        } finally {
            main.post(new Runnable() {
                @Override
                public void run() {
                    main.removeCallbacks(extractRunnable);
                    if (webView != null) {
                        webView.stopLoading();
                        webView.loadUrl("about:blank");
                    }
                }
            });
        }
        if (httpError == 403 || httpError == 429) throw new StockChecker.RateLimitedException(httpError);
        if (httpError >= 400) throw new IOException("HTTP " + httpError);
        if (loadError != null) throw new IOException(loadError);
        if (html == null) throw new IOException("페이지 내용을 읽지 못함");
        return html;
    }

    void destroy() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.destroy();
                    webView = null;
                }
            }
        });
    }

    private final Runnable extractRunnable = new Runnable() {
        @Override
        public void run() {
            if (extracting || webView == null) return;
            extracting = true;
            main.removeCallbacks(this);
            webView.evaluateJavascript(EXTRACT_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    html = decodeJsString(value);
                    done.countDown();
                }
            });
        }
    };

    private WebView ensureWebView() {
        if (webView != null) return webView;
        CookieManager.getInstance().setAcceptCookie(true);
        webView = new WebView(context);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false); // 데이터 절약
        s.setBlockNetworkImage(true);
        // WebView 표시("; wv")를 빼서 일반 크롬과 같게 보이게 한다
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")"));
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 네이버 앱 열기(intent://) 같은 이동은 막고, 웹 페이지 이동만 허용
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if ("about:blank".equals(url) || extracting) return;
                main.removeCallbacks(extractRunnable);
                main.postDelayed(extractRunnable, SETTLE_MS);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) httpError = response.getStatusCode();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // 메인 페이지 자체를 못 연 경우 (인터넷 끊김 등)
                if (failingUrl != null && failingUrl.equals(view.getOriginalUrl())) loadError = description;
            }
        });
        return webView;
    }

    /** evaluateJavascript 결과는 JSON 문자열 ("..." 형태) 로 온다. */
    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            return new JSONArray("[" + value + "]").getString(0);
        } catch (JSONException e) {
            return value;
        }
    }
}
