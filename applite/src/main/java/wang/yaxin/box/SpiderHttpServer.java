package wang.yaxin.box;

import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Exposes the loaded jar spiders over the spider-proxy contract the Yaxin Box server
 * calls, keyed by site in the path:
 *   GET  /spider/_register?key=&api=&ext=&jar=   register/load a csp_ site
 *   GET  /spider/{key}/home?filter=
 *   GET  /spider/{key}/category?tid=&pg=&filter=&extend=
 *   GET  /spider/{key}/detail?ids=
 *   GET  /spider/{key}/search?wd=&pg=&quick=
 *   GET  /spider/{key}/play?flag=&id=
 */
public class SpiderHttpServer extends NanoHTTPD {

    private static final String PREFIX = "/spider/";
    private final JarSpiderHost host;
    private final WebSniffer sniffer;

    public SpiderHttpServer(int port, JarSpiderHost host) {
        super(port);
        this.host = host;
        this.sniffer = new WebSniffer(host.context());
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || !uri.startsWith(PREFIX)) return json("{}");
        Map<String, String> p = session.getParms();
        String path = uri.substring(PREFIX.length());
        try {
            if (path.equals("_register")) {
                host.register(req(p, "key"), req(p, "api"), p.get("ext"), req(p, "jar"));
                return json("{\"ok\":true}");
            }
            if (path.equals("_sniff")) {
                // Resolve a parse-required web page to a real media URL via a hidden WebView.
                String page = req(p, "url");
                long timeout = 15000;
                try { if (p.get("timeout") != null) timeout = Long.parseLong(p.get("timeout")); } catch (Exception ignored) {}
                String real = sniffer.sniff(page, WebSniffer.parseHeaders(p.get("headers")), timeout);
                if (real == null || real.isEmpty()) return error(Response.Status.NOT_FOUND, "sniff_no_media");
                return json("{\"url\":" + JSONObject.quote(real) + "}");
            }
            int slash = path.lastIndexOf('/');
            if (slash < 1) return error(Response.Status.BAD_REQUEST, "bad_path");
            String key = decode(path.substring(0, slash));
            String action = path.substring(slash + 1);
            Spider spider = host.get(key);
            if (spider == null) return error(Response.Status.NOT_FOUND, "not_registered");
            String body;
            switch (action) {
                case "home" -> body = spider.homeContent(bool(p, "filter", true));
                case "category" -> body = spider.categoryContent(str(p, "tid"), str(p, "pg", "1"), bool(p, "filter", false), extend(p.get("extend")));
                case "detail" -> body = spider.detailContent(Arrays.asList(str(p, "ids").split(",")));
                case "search" -> body = search(spider, str(p, "wd"), bool(p, "quick", false), str(p, "pg", "1"));
                case "play" -> body = spider.playerContent(str(p, "flag"), str(p, "id"), Collections.emptyList());
                default -> {
                    return error(Response.Status.NOT_FOUND, "no_action");
                }
            }
            return json(body == null || body.isEmpty() ? "{}" : body);
        } catch (Throwable e) {
            return error(Response.Status.INTERNAL_ERROR, e.getMessage() == null ? "spider_error" : e.getMessage());
        }
    }

    private static String search(Spider s, String wd, boolean quick, String pg) throws Exception {
        String paged = s.searchContent(wd, quick, pg);
        if (paged != null && !paged.isEmpty()) return paged;
        return s.searchContent(wd, quick);
    }

    private Response json(String b) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", b);
    }

    private Response error(Response.Status status, String msg) {
        return newFixedLengthResponse(status, "application/json", "{\"error\":" + JSONObject.quote(msg) + "}");
    }

    private static String req(Map<String, String> p, String k) {
        String v = p.get(k);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("missing_" + k);
        return v;
    }

    private static String str(Map<String, String> p, String k) {
        return str(p, k, "");
    }

    private static String str(Map<String, String> p, String k, String def) {
        String v = p.get(k);
        return v == null ? def : v;
    }

    private static boolean bool(Map<String, String> p, String k, boolean def) {
        String v = p.get(k);
        return v == null ? def : v.equals("true");
    }

    private static String decode(String v) {
        try {
            return URLDecoder.decode(v, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return v;
        }
    }

    private static HashMap<String, String> extend(String json) {
        HashMap<String, String> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                map.put(k, o.optString(k));
            }
        } catch (Exception ignored) {
        }
        return map;
    }
}
