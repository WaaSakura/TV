package wang.yaxin.box;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Native fullscreen player. The web UI resolves a stream (direct URL + the
 * source's required request headers) and hands it here through the JS bridge,
 * because the device WebView's &lt;video&gt; element can be too old (Chromium 66
 * on some players) to decode spider streams. ExoPlayer applies the headers to
 * every request (manifest + HLS/DASH segments), which the WebView never did.
 */
@UnstableApi
public class NativePlayerActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_HEADERS = "headers"; // JSON object string
    public static final String EXTRA_TITLE = "title";

    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout topBar;
    private LinearLayout errorBox;
    private TextView errorText;

    private String url;
    private String title;
    private Map<String, String> headers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        url = getIntent().getStringExtra(EXTRA_URL);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        headers = parseHeaders(getIntent().getStringExtra(EXTRA_HEADERS));
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "no stream url", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(buildUi());
        buildPlayer();
        startPlayback();
    }

    // ---- UI -----------------------------------------------------------------

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setKeepScreenOn(true);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Top bar: back arrow + title, overlaid on the video. Auto-hides with the
        // player controls so it doesn't cover the picture during playback.
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0x99000000);
        int padH = dp(14), padV = dp(10);
        topBar.setPadding(padH, padV, padH, padV);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setPadding(0, 0, dp(16), 0);
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        TextView titleView = new TextView(this);
        titleView.setText(TextUtils.isEmpty(title) ? "" : title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        root.addView(topBar, topLp);

        // Keep the title bar in lockstep with the media3 controller overlay.
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        topBar.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE));

        // Error overlay, centered, hidden until a playback error fires.
        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setVisibility(View.GONE);
        errorBox.setBackgroundColor(0xCC000000);
        errorBox.setPadding(dp(24), dp(24), dp(24), dp(24));

        errorText = new TextView(this);
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        errorText.setGravity(Gravity.CENTER);
        errorBox.addView(errorText);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(16);
        errorBox.addView(btnRow, rowLp);

        Button retry = accentButton("重试", 0xFFC5F02E, Color.BLACK);
        retry.setOnClickListener(v -> startPlayback());
        btnRow.addView(retry);

        Button close = accentButton("返回", 0x33FFFFFF, Color.WHITE);
        close.setOnClickListener(v -> finish());
        btnRow.addView(close);

        root.addView(errorBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private Button accentButton(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, dp(8), 0);
        b.setLayoutParams(lp);
        b.setMinWidth(dp(96));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- Player -------------------------------------------------------------

    private void buildPlayer() {
        // User-Agent goes on the factory; everything else is a default request
        // property applied to manifest and segment requests alike.
        String userAgent = null;
        Map<String, String> requestProps = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("user-agent".equalsIgnoreCase(e.getKey())) userAgent = e.getValue();
            else requestProps.put(e.getKey(), e.getValue());
        }

        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(buildHttpClient());
        if (!requestProps.isEmpty()) httpFactory.setDefaultRequestProperties(requestProps);
        if (!TextUtils.isEmpty(userAgent)) httpFactory.setUserAgent(userAgent);

        DataSource.Factory dataSourceFactory = httpFactory;
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                showError(friendlyError(error));
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    errorBox.setVisibility(View.GONE);
                }
            }
        });
    }

    private void startPlayback() {
        if (player == null) return;
        errorBox.setVisibility(View.GONE);
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        String mime = guessMime(url);
        if (mime != null) item.setMimeType(mime);
        if (!TextUtils.isEmpty(title)) {
            item.setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        }
        player.setMediaItem(item.build());
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void showError(String message) {
        errorText.setText(message);
        errorBox.setVisibility(View.VISIBLE);
    }

    // media3 groups error codes by thousand: 2xxx I/O, 3xxx parsing, 4xxx decode.
    private static String friendlyError(PlaybackException e) {
        int c = e.errorCode;
        if (c == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || c == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                || c == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return "无法访问该视频源，链接可能已失效或需要权限。";
        }
        if (c >= 2000 && c < 3000) return "网络连接失败，请检查网络后重试。";
        if (c >= 3000 && c < 4000) return "该视频封装格式无法解析。";
        if (c >= 4000 && c < 5000) return "解码失败：设备可能不支持该视频编码。";
        return "播放失败（" + e.getErrorCodeName() + "）。";
    }

    // OkHttp follows redirects itself; a network interceptor runs once per hop.
    // When a hop lands on a different host than the call's original request (i.e.
    // we followed a redirect off-origin), strip the origin-scoped headers. This
    // fixes cloud/alist links (list.host → 302 → CDN that 403s the origin Referer)
    // WITHOUT breaking sources like BiliBili, whose cross-host Referer sits on the
    // initial request (same host as the call) and must be kept.
    private static OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Request req = chain.request();
                    HttpUrl origin = chain.call().request().url();
                    if (!req.url().host().equals(origin.host())) {
                        req = req.newBuilder()
                                .removeHeader("Referer")
                                .removeHeader("Cookie")
                                .removeHeader("Origin")
                                .build();
                    }
                    return chain.proceed(req);
                })
                .build();
    }

    // Extensionless URLs are common; MIME hints let DefaultMediaSourceFactory
    // pick the HLS/DASH source without sniffing. Progressive mp4/mkv is inferred.
    private static String guessMime(String url) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        if (path.endsWith(".m3u8") || u.contains(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (path.endsWith(".mpd") || u.contains(".mpd")) return MimeTypes.APPLICATION_MPD;
        return null;
    }

    private static Map<String, String> parseHeaders(String json) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(json)) return map;
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                String v = o.optString(k);
                if (!TextUtils.isEmpty(k) && v != null) map.put(k, v);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A new play request while already open: swap the stream in place.
        url = intent.getStringExtra(EXTRA_URL);
        title = intent.getStringExtra(EXTRA_TITLE);
        headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS));
        if (!TextUtils.isEmpty(url)) startPlayback();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
