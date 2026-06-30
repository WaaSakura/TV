package com.fongmi.android.tv.yaxin;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Starts the embedded Yaxin Box Node server (bundled at assets/nodejs-project) on-device
 * via nodejs-mobile. The server listens on 127.0.0.1:9978 and the WebView loads its /app.
 *
 * REQUIRES the nodejs-mobile native libraries to be added to the app module:
 *   - libnode.so (per ABI) + the nodejs-mobile JNI helper exposing
 *     `startNodeWithArguments(String[])`  (see https://github.com/nodejs-mobile/nodejs-mobile).
 * This class declares the native method + loads the libs; wire the .so/AAR in build.gradle.
 */
public final class NodeRuntime {

    static {
        // Provided by nodejs-mobile integration (names depend on the chosen artifact).
        System.loadLibrary("node");
        System.loadLibrary("nodejs-mobile");
    }

    private static volatile boolean started = false;

    /** JNI entry implemented by the nodejs-mobile native helper. */
    public static native int startNodeWithArguments(String[] arguments);

    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        File projectDir = new File(context.getFilesDir(), "nodejs-project");
        // Copy the bundled project out of (read-only) assets to a writable dir on first run.
        copyAssetDir(context, "nodejs-project", projectDir);
        File main = new File(projectDir, "main.js");
        new Thread(() -> startNodeWithArguments(new String[]{"node", main.getAbsolutePath()}),
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
