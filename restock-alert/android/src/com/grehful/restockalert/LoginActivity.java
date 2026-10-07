package com.grehful.restockalert;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 네이버 공식 로그인 페이지를 앱 안에서 연다.
 * 로그인 쿠키는 앱의 WebView 끼리 공유되므로, 여기서 한 번 로그인하면
 * PageLoader 가 상품 페이지를 로그인된 상태로 열 수 있다.
 * 아이디/비밀번호는 네이버 페이지에 직접 입력되고 앱은 읽거나 저장하지 않는다.
 */
public class LoginActivity extends Activity {
    static final String EXTRA_RETURN_URL = "return_url";
    private static final String DEFAULT_RETURN = "https://m.smartstore.naver.com/";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String back = getIntent().getStringExtra(EXTRA_RETURN_URL);
        if (back == null) back = DEFAULT_RETURN;

        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        TextView help = new TextView(this);
        help.setText("네이버에 로그인하세요. 로그인 후 상품 페이지가 보이면 [로그인 완료]를 누르세요.\n"
                + "(\"로그인 상태 유지\"를 켜두면 다시 로그인할 일이 줄어요)");
        help.setPadding(pad, pad, pad, 0);
        root.addView(help);

        Button done = new Button(this);
        done.setText("로그인 완료");
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(done);

        webView = new WebView(this);
        PageLoader.configure(webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        webView.loadUrl("https://nid.naver.com/nidlogin.login?svctype=262144&url=" + Uri.encode(back));
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush(); // 로그인 쿠키를 바로 저장
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
