package wang.yaxin.box;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Starts the embedded Yaxin Box Node server (bundled at assets/nodejs-project) via
 * nodejs-mobile. Requires libnode.so (per ABI) + the JNI bridge (src/main/cpp).
 */
public final class NodeRuntime {

    static {
        System.loadLibrary("node");
        System.loadLibrary("native-lib");
    }

    private static volatile boolean started = false;

    public static native int startNodeWithArguments(String[] arguments);

    public static synchronized void start(Context context, String spiderBridgeBase) {
        if (started) return;
        started = true;
        File projectDir = new File(context.getFilesDir(), "nodejs-project");
        copyAssetDir(context, "nodejs-project", projectDir);
        File main = new File(projectDir, "main.js");
        String bridge = spiderBridgeBase == null ? "" : spiderBridgeBase;
        new Thread(() -> startNodeWithArguments(new String[]{"node", main.getAbsolutePath(), bridge}),
                "yaxin-node").start();
    }

    private static void copyAssetDir(Context context, String assetPath, File dest) {
        try {
            String[] children = context.getAssets().list(assetPath);
            if (children == null || children.length == 0) {
                copyAssetFile(context, assetPath, dest);
                return;
            }
            if (!dest.exists() && !dest.mkdirs()) return;
            for (String child : children) {
                copyAssetDir(context, assetPath + "/" + child, new File(dest, child));
            }
        } catch (Exception ignored) {
        }
    }

    private static void copyAssetFile(Context context, String assetPath, File dest) {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }

    private NodeRuntime() {
    }
}
