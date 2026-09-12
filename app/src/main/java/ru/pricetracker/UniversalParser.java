package ru.pricetracker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the real store page in WebView and extracts the product price. */
public class UniversalParser {
    public static class Result {
        public String name = "";
        public String site = "";
        public double price = -1;
    }

    public interface Callback {
        void success(Result result);
        void error(Exception error);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean busy;

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
                    main.postDelayed(() -> extract(url, callback), 4500);
                }
                @Override public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                    fail(callback, "Не удалось открыть страницу: " + description);
                }
            });
            webView.loadUrl(url);
            main.postDelayed(() -> {
                if (busy) fail(callback, "Страница загружается слишком долго");
            }, 25000);
        });
    }

    private void extract(String originalUrl, Callback callback) {
        if (!busy || webView == null) return;
        String js = "(function(){" +
                "var q=function(s){var e=document.querySelector(s);return e?(e.content||e.getAttribute('content')||e.innerText||''):''};" +
                "var ld=[];document.querySelectorAll('script[type=\\\"application/ld+json\\\"]').forEach(function(e){ld.push(e.textContent)});" +
                "return JSON.stringify({title:document.title,body:document.body?document.body.innerText:''," +
                "html:document.documentElement?document.documentElement.outerHTML:''," +
                "metaPrice:q('meta[property=\\\"product:price:amount\\\"]')," +
                "itemPrice:q('[itemprop=\\\"price\\\"]'),ld:ld});" +
                "})()";
        webView.evaluateJavascript(js, value -> {
            try {
                String raw = unquote(value);
                Result result = parse(raw, originalUrl);
                if (result.price < 0) throw new Exception("Цена товара не найдена");
                if (result.name.trim().isEmpty()) result.name = domain(originalUrl);
                finish();
                callback.success(result);
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
        String body = field(data, "body");
        String html = field(data, "html");
        String metaPrice = field(data, "metaPrice");
        String itemPrice = field(data, "itemPrice");
        String ld = field(data, "ld");

        String name = firstNonEmpty(
                jsonString(ld, "name"),
                jsonString(html, "name"),
                title.replaceAll("\\s*[|–—-]\\s*.*$", "").trim());
        r.name = clean(name);

        // 1. Explicit price elements are stronger than arbitrary numbers on the page.
        double p = num(metaPrice);
        if (p < 0) p = num(itemPrice);
        if (p >= 1 && p <= 100000000) r.price = p;

        // 2. Prefer an offers.price / lowPrice / priceCurrency structure from JSON-LD.
        if (r.price < 0) {
            p = structuredOfferPrice(ld);
            if (p >= 1 && p <= 100000000) r.price = p;
        }

        // 3. Search visible text. Never turn a parse failure into a tiny price.
        if (r.price < 0) {
            List<Double> candidates = new ArrayList<>();
            addCurrencyPrices(body, candidates);
            r.price = chooseVisiblePrice(candidates, body);
        }

        // 4. Last fallback: price/currentPrice/salePrice followed by a number in HTML/JSON.
        if (r.price < 0) {
            r.price = labeledPrice(html);
        }
        return r;
    }

    private static double structuredOfferPrice(String s) {
        if (s == null) return -1;
        Matcher offer = Pattern.compile("\\\"offers\\\"\\s*:\\s*\\{(.{0,3000}?)\\}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        while (offer.find()) {
            String block = offer.group(1);
            double p = jsonNumber(block, "price");
            if (p < 0) p = jsonNumber(block, "lowPrice");
            if (p >= 1 && p <= 100000000) return p;
        }
        return -1;
    }

    private static double labeledPrice(String s) {
        Matcher m = Pattern.compile("(?:price|salePrice|currentPrice|sellingPrice|\\\"price\\\")\\s*[:=]\\s*\\\"?([0-9]{1,7}(?:[.,][0-9]{1,2})?)",
                Pattern.CASE_INSENSITIVE).matcher(s == null ? "" : s);
        while (m.find()) {
            double p = num(m.group(1));
            if (p >= 1 && p <= 100000000) return p;
        }
        return -1;
    }

    private static void addCurrencyPrices(String text, List<Double> out) {
        if (text == null) return;
        Matcher m = Pattern.compile("(?<!\\d)(\\d{1,3}(?:[\\s.]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?\\s*(?:₽|руб(?:лей|ля)?\\.?|RUB)", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find() && out.size() < 100) {
            String raw = m.group(1).replace(" ", "").replace(".", "");
            double p = num(raw);
            if (p >= 5 && p <= 100000000) out.add(p);
        }
    }

    private static double chooseVisiblePrice(List<Double> candidates, String body) {
        if (candidates.isEmpty()) return -1;
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        // Prefer a candidate next to words that normally describe the selling price.
        for (double p : candidates) {
            String n = String.valueOf((long) p);
            int from = lower.indexOf(n);
            if (from >= 0) {
                int a = Math.max(0, from - 100), b = Math.min(lower.length(), from + n.length() + 100);
                String near = lower.substring(a, b);
                if (near.contains("цена") || near.contains("стоимость") || near.contains("заказ") ||
                        near.contains("купить") || near.contains("итого") || near.contains("скид")) return p;
            }
        }
        // If no label is available, use the first genuine currency amount, never a 1-ruble artifact.
        return candidates.get(0);
    }

    private static double jsonNumber(String s, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"?([0-9]{1,9}(?:[.,][0-9]{1,2})?)", Pattern.CASE_INSENSITIVE).matcher(s == null ? "" : s);
        return m.find() ? num(m.group(1)) : -1;
    }

    private static String jsonString(String s, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s == null ? "" : s);
        return m.find() ? decode(m.group(1)) : "";
    }

    private static String firstNonEmpty(String... values) {
        for (String s : values) if (s != null && !s.trim().isEmpty()) return s;
        return "";
    }

    private static String clean(String s) {
        return s == null ? "" : decode(s).replaceAll("\\s+", " ").trim();
    }

    private static double num(String s) {
        try {
            if (s == null || s.trim().isEmpty()) return -1;
            return Double.parseDouble(s.trim().replace(" ", "").replace(",", "."));
        } catch (Exception e) { return -1; }
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"", Pattern.DOTALL).matcher(json == null ? "" : json);
        return m.find() ? decode(m.group(1)) : "";
    }

    private static String unquote(String s) {
        if (s == null) return "";
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        }
        return s;
    }

    private static String decode(String s) {
        if (s == null) return "";
        return s.replace("\\u002F", "/").replace("\\u0026", "&")
                .replace("\\u003C", "<").replace("\\u003E", ">")
                .replace("\\u0022", "\"");
    }

    private static String domain(String url) {
        try { return new URL(url).getHost(); }
        catch (Exception e) { return url; }
    }

    private void fail(Callback callback, String message) {
        finish();
        callback.error(new Exception(message));
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
