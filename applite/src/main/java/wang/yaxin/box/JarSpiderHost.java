package wang.yaxin.box;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Response;

/**
 * Loads TVBox `csp_` jar spiders at runtime (Android ART) against the real catvod
 * {@link Spider} base (from the :catvod module), so they run exactly as in TVBox.
 *
 * The Yaxin Box Node server registers each csp_ site once (jar URL + api + ext); the
 * spider is then driven by key.
 */
public final class JarSpiderHost {

    private final Context context;
    private final Map<String, Spider> spiders = new ConcurrentHashMap<>();
    private final Map<String, ClassLoader> loaders = new ConcurrentHashMap<>();

    public JarSpiderHost(Context context) {
        this.context = context.getApplicationContext();
    }

    /** api e.g. "csp_XiaoYa"; jar e.g. "http://host/spider.jar;md5;abc" (suffix ignored). */
    public synchronized Spider register(String key, String api, String ext, String jar) throws Exception {
        Spider existing = spiders.get(key);
        if (existing != null) return existing;
        File jarFile = downloadJar(jar);
        ClassLoader loader = loaderFor(jarFile);
        String className = api.startsWith("csp_") ? "com.github.catvod.spider." + api.substring(4) : api;
        Spider spider = (Spider) loader.loadClass(className).getDeclaredConstructor().newInstance();
        // FongMi sets siteKey before init; some spiders (Guard/DexNative variants)
        // read it during init. Best-effort — field is public on the catvod base.
        try {
            spider.getClass().getField("siteKey").set(spider, key);
        } catch (Throwable ignored) {
        }
        spider.init(context, ext == null ? "" : ext);
        spiders.put(key, spider);
        return spider;
    }

    public Spider get(String key) {
        return spiders.get(key);
    }

    public Context context() {
        return context;
    }

    /** Call the jar's own com.github.catvod.spider.Init.init(Context) if present. */
    private void invokeJarInit(ClassLoader loader) {
        try {
            Class<?> init = loader.loadClass("com.github.catvod.spider.Init");
            init.getMethod("init", Context.class).invoke(null, context);
        } catch (Throwable ignored) {
            // Not all jars ship an Init; pure-Java spiders don't need it.
        }
    }

    private ClassLoader loaderFor(File jarFile) {
        String path = jarFile.getAbsolutePath();
        ClassLoader cached = loaders.get(path);
        if (cached != null) return cached;
        // Android 14+ (API 34) enforces W^X on DexClassLoader: a writable dex is
        // rejected with "Writable dex file ... is not allowed". Mark the jar
        // read-only before loading, exactly as FongMi's JarLoader does.
        jarFile.setReadOnly();
        File opt = new File(context.getCacheDir(), "spider-dex");
        if (!opt.exists()) opt.mkdirs();
        ClassLoader loader = new dalvik.system.DexClassLoader(path, opt.getAbsolutePath(), null, JarSpiderHost.class.getClassLoader());
        // FongMi's JarLoader calls the jar's own Init.init(Context) right after
        // loading, once per jar. Guard/DexNative spiders (e.g. the .jpg-disguised
        // jars) NPE in their static init without this global context set up.
        invokeJarInit(loader);
        loaders.put(path, loader);
        return loader;
    }

    private File downloadJar(String jar) throws Exception {
        String url = jar.contains(";") ? jar.substring(0, jar.indexOf(';')) : jar;
        File dir = new File(context.getCacheDir(), "spider-jars");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, Integer.toHexString(url.hashCode()) + ".jar");
        if (out.exists() && out.length() > 0) return out;
        try (Response res = OkHttp.newCall(url).execute()) {
            if (res.body() == null) throw new IllegalStateException("empty jar body");
            byte[] bytes = res.body().bytes();
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(bytes);
            }
        }
        return out;
    }
}
