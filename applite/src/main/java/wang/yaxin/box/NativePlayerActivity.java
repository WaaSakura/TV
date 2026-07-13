package wang.yaxin.box;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = new PlayerView(this);
        playerView.setKeepScreenOn(true);
        playerView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(playerView);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "no stream url", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Map<String, String> headers = parseHeaders(getIntent().getStringExtra(EXTRA_HEADERS));

        // User-Agent is set on the factory (not defaultRequestProperties, which
        // DefaultHttpDataSource ignores for UA); everything else is a default
        // request property applied to manifest and segment requests alike.
        String userAgent = null;
        Map<String, String> requestProps = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("user-agent".equalsIgnoreCase(e.getKey())) userAgent = e.getValue();
            else requestProps.put(e.getKey(), e.getValue());
        }

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true);
        if (!requestProps.isEmpty()) httpFactory.setDefaultRequestProperties(requestProps);
        if (!TextUtils.isEmpty(userAgent)) httpFactory.setUserAgent(userAgent);

        DataSource.Factory dataSourceFactory = httpFactory;
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        playerView.setPlayer(player);

        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        String mime = guessMime(url);
        if (mime != null) item.setMimeType(mime);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (!TextUtils.isEmpty(title)) {
            item.setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder().setTitle(title).build());
        }

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(NativePlayerActivity.this,
                        "播放失败: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }
        });
        player.setMediaItem(item.build());
        player.setPlayWhenReady(true);
        player.prepare();
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
        // A new play request while already open: rebuild by recreating.
        recreate();
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
