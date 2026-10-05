package ru.pricetracker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the store page in a WebView and extracts the product price. */
public class UniversalParser {
    public static class Result {
        public String name = "";
        public String site = "";
        public double price = -1;
        public double noCardPrice = -1;
        public double cardPrice = -1;
    }
    public interface Callback { void success(Result result); void error(Exception error); }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean busy;
    private String yandexState = "";

    public UniversalParser(Context context) { this.context = context; }

    @SuppressLint("SetJavaScriptEnabled")
    public void product(String url, Callback callback) {
        main.post(() -> {
            if (busy) { callback.error(new Exception("Парсер занят")); return; }
            busy = true;
            try {
                webView = new WebView(context);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                webView.getSettings().setDatabaseEnabled(true);
                webView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                webView.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String loadedUrl) { main.postDelayed(() -> prepareAndExtract(url, callback), isYandexMarket(url) ? 1200 : 4500); }
                    @Override public void onReceivedError(WebView view, int code, String description, String failingUrl) { fail(callback, "Не удалось открыть страницу: " + description); }
                });
                webView.loadUrl(url);
                main.postDelayed(() -> { if (busy) fail(callback, "Страница загружается слишком долго"); }, 60000);
            } catch (Exception e) { fail(callback, e.getMessage() == null ? "Не удалось создать WebView" : e.getMessage()); }
        });
    }

    private void prepareAndExtract(String originalUrl, Callback callback) {
        if (isYandexMarket(originalUrl)) {
            String js = "(function(){try{var C={price:{},buyOption:{},productCardMeta:{},popupInfo:{}};" +
                    "document.querySelectorAll('noframes[data-apiary=\\\"patch\\\"]').forEach(function(e){try{var d=JSON.parse(e.textContent||'{}');var cc=d.collections||{};" +
                    "Object.keys(C).forEach(function(n){var x=cc[n];if(x&&typeof x==='object')Object.keys(x).forEach(function(k){C[n][k]=x[k]});});}catch(x){}});" +
                    "var first=function(o){var a=Object.keys(o);for(var i=0;i<a.length;i++){var v=o[a[i]];if(v&&typeof v==='object')return v;}return {};};" +
                    "var pr=first(C.price),bo=first(C.buyOption),meta=first(C.productCardMeta),pop={};" +
                    "Object.keys(C.popupInfo).some(function(k){var v=C.popupInfo[k];if(v&&v.type==='priceDetails'){pop=(v.params||{}).priceDetails||{};return true;}return false;});" +
                    "var main=pr.mainPrice||{}, mainPrice=main.price?main.price.value:null, isPay=main.subtype==='ya-card';" +
                    "var old={}, oo=pr.oldPrices||[];oo.forEach(function(x){if(x&&x.type)old[x.type]=x.price?x.price.value:null;});" +
                    "var cart=bo.price?bo.price.value:null;" +
                    "var pay=pop.pay!=null?pop.pay:(isPay?mainPrice:null);" +
                    "var derived=isPay?(old.regular!=null?old.regular:(old.withoutDiscount!=null&&Object.keys(old).length===1?old.withoutDiscount:null)):(main.subtype?null:mainPrice);" +
                    "var noCard=pop.no_card!=null?pop.no_card:(cart!=null?cart:derived);" +
                    "var before=pop.before!=null?pop.before:((!pop&&isPay)?old.withoutDiscount:null);" +
                    "return JSON.stringify({noCard:noCard,pay:pay,before:before,cart:cart,main:mainPrice,mainSubtype:main.subtype||'',title:meta.title||''});" +
                    "}catch(e){return JSON.stringify({error:String(e)})}})()";
            webView.evaluateJavascript(js, value -> { yandexState = unquote(value); if (jsonNumber(yandexState, "noCard") >= 1 || jsonNumber(yandexState, "pay") >= 1 || !jsonString(yandexState, "title").trim().isEmpty()) { main.postDelayed(() -> extract(originalUrl, callback), 300); } else { main.postDelayed(() -> prepareAndExtract(originalUrl, callback), 1200); } });
            return;
        }
        if (!isOzon(originalUrl)) { extract(originalUrl, callback); return; }
        String js = "(function(){try{if(window.__ptOzonApiStarted)return 'started';window.__ptOzonApiStarted=true;fetch('/api/composer-api.bx/page/json/v2?url='+encodeURIComponent(location.pathname+location.search),{credentials:'include'}).then(function(r){return r.text()}).then(function(t){window.__ptOzonApi=t}).catch(function(){window.__ptOzonApi=''});return 'started';}catch(e){return 'error'}})()";
        webView.evaluateJavascript(js, value -> main.postDelayed(() -> extract(originalUrl, callback), 2500));
    }

    private void extract(String originalUrl, Callback callback) {
        if (!busy || webView == null) return;
        String js = "(function(){" +
                "var q=function(s){var e=document.querySelector(s);return e?(e.content||e.getAttribute('content')||e.innerText||''):''};" +
                "var first=function(s){var e=document.querySelector(s);return e?(e.innerText||e.textContent||''):''};" +
                "var ld=[];document.querySelectorAll('script[type=\\\"application/ld+json\\\"]').forEach(function(e){ld.push(e.textContent)});var ozState='';var ozEl=document.querySelector('[id^=\\\"state-webPrice\\\"]');if(ozEl&&ozEl.dataset)ozState=ozEl.dataset.state||'';" +
                "var ym=isYandex(document.location.href);" +
                "if(ym)return JSON.stringify({title:document.title,body:document.body?document.body.innerText.slice(0,60000):'',metaPrice:q('meta[property=\\\"product:price:amount\\\"]'),itemPrice:q('[itemprop=\\\"price\\\"]'),ogTitle:q('meta[property=\\\"og:title\\\"]'),yandexState:(window.__ptYandexState||''),ld:ld.join('\\n')});" +
                "return JSON.stringify({title:document.title,body:document.body?document.body.innerText:'',html:document.documentElement?document.documentElement.outerHTML:'',metaPrice:q('meta[property=\\\"product:price:amount\\\"]'),itemPrice:q('[itemprop=\\\"price\\\"]'),ozonPrice:first('[data-widget=\\\"webPrice\\\"] .tsHeadline600Large, [data-widget=\\\"webPrice\\\"] span, [data-widget=\\\"webOzonAccountPrice\\\"] .tsHeadline600Large'),ozonState:ozState,ozonApi:(window.__ptOzonApi||''),yandexState:(window.__ptYandexState||''),ld:ld.join('\\n')});" +
                "function isYandex(u){return /market\\.yandex\\./i.test(u)}" +
                "})()";
        webView.evaluateJavascript(js, value -> {
            try {
                String raw = unquote(value);
                Result result = parse(raw, originalUrl);
                if (isYandexMarket(originalUrl)) {
                    double yp = jsonNumber(yandexState, "noCard");
                    if (validPrice(yp)) result.price = yp;
                    String yt = jsonString(yandexState, "title");
                    if (result.name.trim().isEmpty() && !yt.trim().isEmpty()) result.name = clean(yt);
                }
                if (result.price < 0) throw new Exception("Цена товара не найдена");
                if (result.name.trim().isEmpty()) result.name = domain(originalUrl);
                finish(); callback.success(result);
            } catch (Exception e) { finish(); callback.error(e); }
        });
    }

    private Result parse(String data, String url) {
        Result r = new Result(); r.site = domain(url);
        String title = field(data, "title"), body = field(data, "body"), html = field(data, "html"), ogTitle = field(data, "ogTitle");
        String metaPrice = field(data, "metaPrice"), itemPrice = field(data, "itemPrice"), ozonPrice = field(data, "ozonPrice"), ozonState = field(data, "ozonState"), ozonApi = field(data, "ozonApi"), yandexState = field(data, "yandexState"), ld = field(data, "ld");
        String name = firstNonEmpty(jsonString(ld, "name"), jsonString(html, "name"), ogTitle, title.replaceAll("\\s*[|–—-]\\s*.*$", "").trim());
        r.name = clean(name);

        if (isYandexMarket(url)) {
            double yp = jsonNumber(yandexState, "noCard");
            if (validPrice(yp)) r.price = yp;
            if (r.name.trim().isEmpty()) r.name = jsonString(yandexState, "title");
        }

        // Ozon puts the actual visible current price into the webPrice widget.
        // Read that widget before scanning the whole page, because descriptions
        // and characteristics can contain unrelated amounts such as "Баланс 1700 руб.".
        if (isOzon(url)) {
            r.price = chooseOzonPrice(ozonApi, ld, ozonPrice, ozonState);
        }

        double p = num(metaPrice); if (r.price < 0 && p >= 1 && p <= 100000000) r.price = p;
        p = num(itemPrice); if (r.price < 0 && p >= 1 && p <= 100000000) r.price = p;
        if (r.price < 0) { p = structuredOfferPrice(ld); if (p >= 1 && p <= 100000000) r.price = p; }
        if (r.price < 0) { List<Double> candidates = new ArrayList<>(); addCurrencyPrices(body, candidates); r.price = chooseVisiblePrice(candidates, body); }
        if (r.price < 0) r.price = labeledPrice(html);
        return r;
    }

    private static double chooseOzonPrice(String api, String ld, String visible, String state) {
        double apiPrice = structuredOfferPrice(api);
        double ldPrice = structuredOfferPrice(ld);
        double visiblePrice = priceFromText(visible);
        double statePrice = jsonNumber(state, "price");

        if (validPrice(apiPrice) && validPrice(ldPrice) && samePrice(apiPrice, ldPrice)) return apiPrice;
        if (validPrice(apiPrice) && !validPrice(ldPrice)) return apiPrice;
        if (validPrice(ldPrice) && !validPrice(apiPrice)) return ldPrice;

        if (validPrice(visiblePrice) &&
                (samePrice(visiblePrice, apiPrice) || samePrice(visiblePrice, ldPrice))) {
            return visiblePrice;
        }
        if (validPrice(apiPrice)) return apiPrice;
        if (validPrice(ldPrice)) return ldPrice;
        if (validPrice(visiblePrice)) return visiblePrice;
        if (validPrice(statePrice)) return statePrice;
        return -1;
    }

    private static boolean validPrice(double p) { return p >= 1 && p <= 100000000; }
    private static boolean samePrice(double a, double b) { return validPrice(a) && validPrice(b) && Math.abs(a - b) < 0.01; }

    private static boolean isYandexMarket(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("market.yandex.");
    }

    private static boolean isOzon(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("ozon.");
    }

    private static double priceFromText(String text) {
        if (text == null || text.trim().isEmpty()) return -1;
        // The first price in Ozon's webPrice span is the current visible price.
        Matcher m = Pattern.compile("(?<!\\d)(\\d{1,3}(?:(?:[\\s\\u00A0\\u202F.]\\s*)\\d{3})+|\\d+)(?:[.,]\\d{1,2})?\\s*(?:₽|руб(?:лей|ля)?\\.?|RUB)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            String raw = m.group(1).replaceAll("[\\s\\u00A0\\u202F.]", "");
            return num(raw);
        }
        return -1;
    }

    private static double structuredOfferPrice(String s) {
        if (s == null) return -1;
        Matcher offer = Pattern.compile("\\\"offers\\\"\\s*:\\s*\\{(.{0,3000}?)\\}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        while (offer.find()) { String block = offer.group(1); double p = jsonNumber(block, "price"); if (p < 0) p = jsonNumber(block, "lowPrice"); if (p >= 1 && p <= 100000000) return p; }
        return -1;
    }

    private static double labeledPrice(String s) {
        Matcher m = Pattern.compile("(?:price|salePrice|currentPrice|sellingPrice|\\\"price\\\")\\s*[:=]\\s*\\\"?([0-9]{1,9}(?:[.,][0-9]{1,2})?)", Pattern.CASE_INSENSITIVE).matcher(s == null ? "" : s);
        while (m.find()) { double p = num(m.group(1)); if (p >= 1 && p <= 100000000) return p; }
        return -1;
    }

    private static void addCurrencyPrices(String text, List<Double> out) {
        if (text == null) return;
        // Handles 1234, 12 345, 12\u00A0345, 12\u202F345 and 12.345 ₽.
        Matcher m = Pattern.compile("(?<!\\d)(\\d{1,3}(?:(?:[\\s\\u00A0\\u202F.]\\s*)\\d{3})+|\\d+)(?:[.,]\\d{1,2})?\\s*(?:₽|руб(?:лей|ля)?\\.?|RUB)", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find() && out.size() < 100) {
            String raw = m.group(1).replaceAll("[\\s\\u00A0\\u202F.]", "");
            double p = num(raw);
            if (p >= 5 && p <= 100000000) out.add(p);
        }
    }

    private static double chooseVisiblePrice(List<Double> candidates, String body) {
        if (candidates.isEmpty()) return -1;
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        for (double p : candidates) {
            String n = String.valueOf((long) p); int from = lower.indexOf(n);
            if (from >= 0) { int a = Math.max(0, from - 100), b = Math.min(lower.length(), from + n.length() + 100); String near = lower.substring(a, b); if (near.contains("цена") || near.contains("стоимость") || near.contains("заказ") || near.contains("купить") || near.contains("итого") || near.contains("скид")) return p; }
        }
        return candidates.get(0);
    }

    private static double jsonNumber(String s, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"?([0-9]{1,9}(?:[.,][0-9]{1,2})?)", Pattern.CASE_INSENSITIVE).matcher(s == null ? "" : s);
        return m.find() ? num(m.group(1)) : -1;
    }
    private static String jsonString(String s, String key) { Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s == null ? "" : s); return m.find() ? decode(m.group(1)) : ""; }
    private static String firstNonEmpty(String... values) { for (String s : values) if (s != null && !s.trim().isEmpty()) return s; return ""; }
    private static String clean(String s) { return s == null ? "" : decode(s).replaceAll("\\s+", " ").trim(); }
    private static double num(String s) { try { if (s == null || s.trim().isEmpty()) return -1; return Double.parseDouble(s.trim().replaceAll("[\\s\\u00A0\\u202F]", "").replace(",", ".")); } catch (Exception e) { return -1; } }
    private static String field(String json, String key) { Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"", Pattern.DOTALL).matcher(json == null ? "" : json); return m.find() ? decode(m.group(1)) : ""; }
    private static String unquote(String s) { if (s == null) return ""; if (s.startsWith("\"") && s.endsWith("\"")) { s = s.substring(1, s.length() - 1); s = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t"); } return s; }
    private static String decode(String s) { if (s == null) return ""; return s.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\u003C", "<").replace("\\u003E", ">").replace("\\u0022", "\"").replace("\\\"", "\""); }
    private static String domain(String url) { try { return new URL(url).getHost(); } catch (Exception e) { return url; } }
    private void fail(Callback callback, String message) { finish(); callback.error(new Exception(message)); }
    private void finish() { busy = false; if (webView != null) { webView.stopLoading(); webView.destroy(); webView = null; } }
}
