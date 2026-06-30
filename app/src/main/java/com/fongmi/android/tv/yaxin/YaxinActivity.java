package com.fongmi.android.tv.yaxin;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.net.OkHttp;

import okhttp3.Response;

/**
 * The Yaxin Box launcher: boots the embedded Node server (our service) and shows our
 * web UI in a WebView. csp_ / drpy / py spiders are executed by FongMi's native loaders,
 * reached by the Node server's spider-proxy via the in-app SpiderApi localhost bridge.
 *
 * Make this the LAUNCHER activity in AndroidManifest (rebrand step); FongMi's own
 * activities remain available internally.
 */
public class YaxinActivity extends Activity {

    private static final String UI_URL = "http://127.0.0.1:9978/app";
    private static final String HEALTH_URL = "http://127.0.0.1:9978/health";

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NodeRuntime.start(getApplicationContext());

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);

        waitForServer(40);
    }

    /** Poll the embedded server's health, then load the UI. */
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
}
