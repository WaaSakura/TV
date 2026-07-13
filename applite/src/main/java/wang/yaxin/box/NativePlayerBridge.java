package wang.yaxin.box;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;

/**
 * Exposed to the web UI as window.YaxinNativePlayer. When present, the page
 * hands playback (resolved URL + the source's request headers) to the native
 * ExoPlayer instead of its own &lt;video&gt; element. @JavascriptInterface methods
 * run on a binder thread, so launching the activity is posted to the UI thread.
 */
public class NativePlayerBridge {

    private final Activity activity;

    public NativePlayerBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void play(String url, String headersJson, String title) {
        if (url == null || url.isEmpty()) return;
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(activity, NativePlayerActivity.class);
            intent.putExtra(NativePlayerActivity.EXTRA_URL, url);
            intent.putExtra(NativePlayerActivity.EXTRA_HEADERS, headersJson);
            intent.putExtra(NativePlayerActivity.EXTRA_TITLE, title);
            activity.startActivity(intent);
        });
    }

    /** Presence probe the web UI can feature-detect. */
    @JavascriptInterface
    public boolean available() {
        return true;
    }
}
