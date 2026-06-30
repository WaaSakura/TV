package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * Bridges the natively-loaded TVBox spiders (jar / js / py via {@link BaseLoader})
 * over HTTP so an external client — e.g. the Yaxin Box server's spider-proxy
 * adapter — can drive them. The site must already be loaded into VodConfig
 * (open the subscription in the app once); spiders are resolved by site key.
 *
 * The site key is carried in the path so the existing spider-proxy contract
 * ({base}/home, {base}/category?..., {base}/detail?ids=, {base}/search?wd=,
 * {base}/play?flag=&id=) maps unchanged onto a per-site base of
 *   spider://<host>:<port>/spider/<urlencoded-site-key>
 *
 * Routes (each returns the spider's TVBox-shaped JSON):
 *   /spider/{key}/home?filter=
 *   /spider/{key}/category?tid=&pg=&filter=&extend=
 *   /spider/{key}/detail?ids=
 *   /spider/{key}/search?wd=&pg=&quick=
 *   /spider/{key}/play?flag=&id=
 */
public class SpiderApi implements Process {

    private static final String PREFIX = "/spider/";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(PREFIX);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String path = url.substring(PREFIX.length());
        int slash = path.lastIndexOf('/');
        if (slash < 1) return Nano.error(Response.Status.BAD_REQUEST, "bad path");
        String key = decode(path.substring(0, slash));
        String action = path.substring(slash + 1);
        if (TextUtils.isEmpty(key)) return Nano.error(Response.Status.BAD_REQUEST, "missing key");
        Map<String, String> params = session.getParms();
        Spider spider = BaseLoader.get().getSpider(key);
        try {
            String json;
            switch (action) {
                case "home" -> json = spider.homeContent(bool(params, "filter", true));
                case "category" -> json = spider.categoryContent(str(params, "tid"), str(params, "pg", "1"), bool(params, "filter", false), extend(params.get("extend")));
                case "detail" -> json = spider.detailContent(Arrays.asList(str(params, "ids").split(",")));
                case "search" -> json = search(spider, str(params, "wd"), bool(params, "quick", false), str(params, "pg", "1"));
                case "play" -> json = spider.playerContent(str(params, "flag"), str(params, "id"), Collections.emptyList());
                default -> {
                    return Nano.error(Response.Status.NOT_FOUND, "not found");
                }
            }
            return json(json);
        } catch (Throwable e) {
            return Nano.error(e.getMessage() == null ? "spider_error" : e.getMessage());
        }
    }

    private static String search(Spider spider, String wd, boolean quick, String pg) throws Exception {
        String paged = spider.searchContent(wd, quick, pg);
        if (!TextUtils.isEmpty(paged)) return paged;
        return spider.searchContent(wd, quick);
    }

    private static Response json(String body) {
        return Nano.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", TextUtils.isEmpty(body) ? "{}" : body);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
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

    private static HashMap<String, String> extend(String json) {
        HashMap<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(json)) return map;
        try {
            JSONObject obj = new JSONObject(json);
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String k = it.next();
                map.put(k, obj.optString(k));
            }
        } catch (Exception ignored) {
        }
        return map;
    }
}
