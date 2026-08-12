package ru.pricetracker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;
import java.util.*;
import java.util.regex.*;

/**
 * Browser-based product parser.
 * It loads the real page in Android WebView and then inspects the rendered DOM,
 * JSON-LD and meta tags. This is much more suitable for modern JS-heavy stores.
 */
public class UniversalParser {
    public static class Result {
        public String name = "";
        public double price = -1;
        public double oldPrice = -1;
        public String site = "";
    }

    public interface Callback {
        void success(Result r);
        void error(Exception e);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean busy = false;

    public UniversalParser(Context context) {
        this.context = context.getApplicationContext();
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void product(String url, Callback callback) {
        main.post(() -> {
            if (busy) {
                callback.error(new Exception("Парсер занят"));
                return;
            }
            busy = true;
            webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setDatabaseEnabled(true);
            webView.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String loadedUrl) {
                    // Modern stores often render price asynchronously. Give them time.
                    main.postDelayed(() -> extract(url, callback), 5000);
                }
                @Override public void onReceivedError(WebView view, int errorCode,
                                                      String description, String failingUrl) {
                    finish();
                    callback.error(new Exception("Не удалось открыть страницу: " + description));
                }
            });
            webView.loadUrl(url);
            main.postDelayed(() -> {
                if (busy) {
                    finish();
                    callback.error(new Exception("Страница загружается слишком долго"));
                }
            }, 20000);
        });
    }

    private void extract(String originalUrl, Callback callback) {
        if (!busy || webView == null) return;
        String js =
            "(function(){" +
            "return JSON.stringify({" +
            "title:document.title," +
            "text:(document.body?document.body.innerText:'')," +
            "html:(document.documentElement?document.documentElement.outerHTML:'')" +
            "});" +
            "})()";
        webView.evaluateJavascript(js, value -> {
            try {
                String raw = unquote(value);
                Result r = parse(raw, originalUrl);
                if (r.price < 0) throw new Exception("Цена не найдена на странице");
                if (r.name == null || r.name.trim().isEmpty()) r.name = domain(originalUrl);
                finish();
                callback.success(r);
            } catch (Exception e) {
                finish();
                callback.error(e);
            }
        });
    }

    private Result parse(String data, String url) {
        Result r = new Result();
        r.site = domain(url);
        String title = field(data, "title");
        String text = field(data, "text");
        String html = field(data, "html");

        // 1) Product JSON-LD. Prefer offers.price.
        List<Double> jsonPrices = new ArrayList<>();
        Matcher pm = Pattern.compile(
            "(?:\"price\"\\s*:\\s*\"?)([0-9]{1,7}(?:[.,][0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE).matcher(html);
        while (pm.find() && jsonPrices.size() < 50) jsonPrices.add(num(pm.group(1)));

        Matcher nm = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]{2,300})\"", Pattern.CASE_INSENSITIVE).matcher(html);
        if (nm.find()) r.name = decode(nm.group(1));

        // 2) Site-specific and generic price candidates from visible text.
        ArrayList<Double> candidates = new ArrayList<>();
        addPrices(text, candidates);
        addPrices(html.replaceAll("<script.*?</script>", " "), candidates);
        candidates.addAll(jsonPrices);

        // Remove absurd candidates and choose the most plausible price.
        candidates.removeIf(v -> v < 1 || v > 100000000);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<Double> unique = new ArrayList<>();
        for (Double v : candidates) {
            String key = String.format(Locale.US, "%.2f", v);
            if (seen.add(key)) unique.add(v);
        }

        // For product pages, JSON-LD price is usually the strongest signal.
        if (!jsonPrices.isEmpty()) {
            for (Double v : jsonPrices) {
                if (v >= 1 && v <= 100000000) { r.price = v; break; }
            }
        }
        if (r.price < 0 && !unique.isEmpty()) {
            r.price = choosePrice(unique, text, url);
        }

        // A conservative old-price guess: a larger candidate near the current one.
        if (r.price > 0) {
            for (Double v : unique) {
                if (v > r.price && v <= r.price * 5.0) {
                    if (r.oldPrice < 0 || v < r.oldPrice) r.oldPrice = v;
                }
            }
        }

        if (r.name == null || r.name.isEmpty()) {
            r.name = title == null ? "" : title.replaceAll("\\s*[|–-]\\s*.*$", "").trim();
        }
        return r;
    }

    private static double choosePrice(List<Double> a, String text, String url) {
        String lower = text.toLowerCase(Locale.ROOT);
        // Prefer candidates occurring close to currency symbols/price labels.
        for (Double v : a) {
            String s = format(v);
            int idx = lower.indexOf(s.replace(".0",""));
            if (idx >= 0) {
                int end = Math.min(lower.length(), idx + 100);
                String near = lower.substring(Math.max(0,idx-80), end);
                if (near.contains("₽") || near.contains("руб") ||
                    near.contains("цена") || near.contains("price")) return v;
            }
        }
        // Avoid likely installment amounts and tiny recommendation prices.
        for (Double v : a) if (v >= 50) return v;
        return a.get(0);
    }

    private static void addPrices(String s, List<Double> out) {
        if (s == null) return;
        Matcher m = Pattern.compile(
            "(?<![\\d])([0-9]{2,7}(?:[ .][0-9]{3})?(?:[.,][0-9]{1,2})?)\\s*(?:₽|руб\\.?|RUB)(?![\\d])",
            Pattern.CASE_INSENSITIVE).matcher(s);
        while (m.find() && out.size() < 200) {
            String x = m.group(1).replace(" ","").replace(".","");
            out.add(num(x));
        }
        Matcher m2 = Pattern.compile(
            "(?:цена|price|salePrice|currentPrice)[^0-9]{0,100}([0-9]{2,7}(?:[.,][0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE).matcher(s);
        while (m2.find() && out.size() < 200) out.add(num(m2.group(1)));
    }

    private static double num(String s) {
        try { return Double.parseDouble(s.replace(" ","").replace(",", ".")); }
        catch(Exception e){ return -1; }
    }
    private static String format(double d){ return String.valueOf((long)d); }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\""+key+"\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            Pattern.DOTALL).matcher(json);
        return m.find() ? decode(m.group(1)) : "";
    }

    private static String unquote(String s) {
        if (s == null) return "";
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length()-1);
            s = s.replace("\\\"", "\"").replace("\\\\", "\\")
                 .replace("\\n","\n").replace("\\r","\r").replace("\\t","\t");
        }
        return s;
    }

    private static String decode(String s) {
        if (s == null) return "";
        return s.replace("\\u002F","/").replace("\\u0026","&")
                .replace("\\u003C","<").replace("\\u003E",">")
                .replace("\\u0022","\"").replace("\\\\\"","\"");
    }

    private static String domain(String url) {
        try { return new java.net.URL(url).getHost(); }
        catch(Exception e){ return url; }
    }

    private void finish() {
        busy = false;
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }
}
