package wang.yaxin.box;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.Init;
import com.github.catvod.net.OkHttp;

import okhttp3.Response;

/**
 * Yaxin Box launcher: starts the native jar-spider bridge + the embedded Node server,
 * then shows our web UI (which plays video itself via hls.js) in a WebView.
 */
public class MainActivity extends Activity {

    // Uncommon ports: 9978/7777 are popular with other TVBox-family apps on the
    // same device (a frozen background app holding the port kills our server).
    private static final int SPIDER_PORT = 27777;
    private static final String UI_URL = "http://127.0.0.1:29978/app";
    private static final String HEALTH_URL = "http://127.0.0.1:29978/health";

    private WebView webView;
    private SpiderHttpServer spiderServer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Init.set(getApplicationContext()); // initialise catvod runtime (OkHttp, etc.)

        JarSpiderHost host = new JarSpiderHost(getApplicationContext());
        try {
            spiderServer = new SpiderHttpServer(SPIDER_PORT, host);
            spiderServer.start(SOCKET_READ_TIMEOUT, true);
        } catch (Exception ignored) {
        }
        NodeRuntime.start(getApplicationContext(), "spider://127.0.0.1:" + SPIDER_PORT + "/spider");

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // WebView ignores the page's <meta viewport> unless wide viewport is on —
        // without it media queries see a bogus layout width and the UI deforms.
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pushSafeAreaInsets();
            }
        });
        // Edge-to-edge: WebView doesn't expose env(safe-area-inset-*), so feed the
        // window insets to the page as CSS variables the stylesheet already uses.
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            float density = getResources().getDisplayMetrics().density;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                safeT = Math.round(bars.top / density);
                safeR = Math.round(bars.right / density);
                safeB = Math.round(bars.bottom / density);
                safeL = Math.round(bars.left / density);
            } else {
                safeT = Math.round(insets.getSystemWindowInsetTop() / density);
                safeR = Math.round(insets.getSystemWindowInsetRight() / density);
                safeB = Math.round(insets.getSystemWindowInsetBottom() / density);
                safeL = Math.round(insets.getSystemWindowInsetLeft() / density);
            }
            pushSafeAreaInsets();
            return insets;
        });
        setContentView(webView);
        waitForServer(40);
    }

    private int safeT, safeR, safeB, safeL; // CSS px

    private void pushSafeAreaInsets() {
        if (webView == null) return;
        String js = "(function(){var s=document.documentElement.style;"
                + "s.setProperty('--safe-t','" + safeT + "px');"
                + "s.setProperty('--safe-r','" + safeR + "px');"
                + "s.setProperty('--safe-b','" + safeB + "px');"
                + "s.setProperty('--safe-l','" + safeL + "px');})()";
        webView.evaluateJavascript(js, null);
    }

    private void waitForServer(int attemptsLeft) {
        new Thread(() -> {
            boolean up = ping();
            handler.post(() -> {
                if (up) webView.loadUrl(UI_URL);
                else if (attemptsLeft > 0) handler.postDelayed(() -> waitForServer(attemptsLeft - 1), 500);
            });
        }, "yaxin-health").start();
    }

    private boolean ping() {
        try (Response res = OkHttp.newCall(HEALTH_URL).execute()) {
            return res.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (spiderServer != null) spiderServer.stop();
        super.onDestroy();
    }

    private static final int SOCKET_READ_TIMEOUT = 15000;
}
