package wang.yaxin.box;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private static final int SPIDER_PORT = 7777;
    private static final String UI_URL = "http://127.0.0.1:9978/app";
    private static final String HEALTH_URL = "http://127.0.0.1:9978/health";

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
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        waitForServer(40);
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
