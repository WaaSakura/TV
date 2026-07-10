package wang.yaxin.box;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Hidden-WebView video sniffer, ported from FongMi's CustomWebView. Loads a
 * "parse-required" web page and watches its network requests; the first one that
 * looks like a real media stream (m3u8/mp4/…) is returned as the play URL.
 *
 * Runs the WebView on the main thread (WebView requires it) and blocks the
 * calling (NanoHTTPD) thread on a latch until a hit, error, or timeout.
 */
public final class WebSniffer {

    // FongMi's Sniffer.SNIFFER pattern.
    private static final Pattern SNIFFER = Pattern.compile(
            "https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?" +
            "|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+");
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;

    public WebSniffer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Blocking sniff. Returns a media URL, or null on error/timeout. */
    public String sniff(String pageUrl, Map<String, String> headers, long timeoutMs) {
        final AtomicReference<String> result = new AtomicReference<>(null);
        final AtomicReference<WebView> webRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        main.post(() -> {
            try {
                WebView web = buildWebView(pageUrl, headers, result, latch);
                webRef.set(web);
                web.loadUrl(pageUrl, headers == null ? new java.util.HashMap<>() : headers);
            } catch (Throwable t) {
                latch.countDown();
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // Tear the WebView down on the main thread regardless of outcome.
        main.post(() -> {
            WebView web = webRef.getAndSet(null);
            if (web != null) destroy(web);
        });
        return result.get();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView buildWebView(String pageUrl, Map<String, String> headers,
                                 AtomicReference<String> result, CountDownLatch latch) {
        WebView web = new WebView(context);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(uaFrom(headers));

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if ("cookie".equalsIgnoreCase(e.getKey())) {
                    CookieManager.getInstance().setCookie(pageUrl, e.getValue());
                }
            }
        }

        WebResourceResponse empty = new WebResourceResponse(
                "text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (result.get() == null && isVideo(url, pageUrl)) {
                    result.set(url);
                    latch.countDown();
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            @SuppressLint("WebViewClientOnReceivedSslError")
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        return web;
    }

    /** Matches FongMi Sniffer.isVideoFormat's core rules. */
    private static boolean isVideo(String url, String pageUrl) {
        if (url == null || url.equals(pageUrl)) return false;
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false;
        return SNIFFER.matcher(url).find();
    }

    private static String uaFrom(Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if ("user-agent".equalsIgnoreCase(e.getKey()) && !TextUtils.isEmpty(e.getValue())) {
                    return e.getValue();
                }
            }
        }
        return DEFAULT_UA;
    }

    private static void destroy(WebView web) {
        try {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.removeAllViews();
            web.destroy();
        } catch (Throwable ignored) {
        }
    }

    /** Parse a JSON headers object (e.g. from the ?headers= query) into a map. */
    public static Map<String, String> parseHeaders(String json) {
        java.util.HashMap<String, String> map = new java.util.HashMap<>();
        if (json == null || json.isEmpty()) return map;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                map.put(k, o.optString(k));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    public static String host(String url) {
        try { return Uri.parse(url).getHost(); } catch (Exception e) { return ""; }
    }
}
