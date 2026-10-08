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
        public String diagnostic = "";
    }
    public interface Callback { void success(Result result); void error(Exception error); }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean busy;
    private String yandexState = "";
    private int yandexAttempts;
    private long yandexStartMs;
    private String yandexDiag = "";

    public UniversalParser(Context context) { this.context = context; }

    @SuppressLint("SetJavaScriptEnabled")
    public void product(String url, Callback callback) {
        main.post(() -> {
            if (busy) { callback.error(new Exception("Парсер занят")); return; }
            busy = true;
            yandexAttempts = 0;
            yandexStartMs = System.currentTimeMillis();
            yandexDiag = "";
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
            // Yandex Market no longer exposes the old cpa-offer/greenPrice structure
            // in this WebView. Read the prices from the visible DOM and classify them
            // by the surrounding UI text instead.
            main.postDelayed(() -> extractYandexPricesFromDom(originalUrl, callback), 300);
            return;
        }
        if (!isOzon(originalUrl)) {
            extract(originalUrl, callback);
            return;
        }
        String js = "(function(){try{if(window.__ptOzonApiStarted)return 'started';window.__ptOzonApiStarted=true;fetch('/api/composer-api.bx/page/json/v2?url='+encodeURIComponent(location.pathname+location.search),{credentials:'include'}).then(function(r){return r.text()}).then(function(t){window.__ptOzonApi=t}).catch(function(){window.__ptOzonApi=''});return 'started';}catch(e){return 'error'}})()";
        webView.evaluateJavascript(js, value -> main.postDelayed(() -> extract(originalUrl, callback), 2500));
    }

    private void extractYandexPricesFromDom(String originalUrl, Callback callback) {
        if (!busy || webView == null) return;

        // Diagnostic only: do not classify prices or write them to state/DB.
        // We need the local DOM structure around the two known product prices.
        String js = "(function(){try{" +
                "var trim=function(s,n){s=String(s||'').replace(/\\s+/g,' ').trim();return s.length>n?s.slice(0,n)+'…':s;};" +
                "var norm=function(s){return String(s||'').replace(/\\s+/g,' ').trim();};" +
                "var visible=function(e){if(!e||!e.getBoundingClientRect)return false;var r=e.getBoundingClientRect(),c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&c.opacity!=='0'&&r.width>0&&r.height>0;};" +
                "var priceRe=/(?:^|[^0-9])(\\d{1,3}(?:[\\s\\u00a0\\u202f]\\d{3})+|\\d{2,7})\\s*₽/g;" +
                "var nums=function(s){var out=[],m;priceRe.lastIndex=0;while((m=priceRe.exec(String(s||'')))!==null){var p=Number(m[1].replace(/[\\s\\u00a0\\u202f]/g,''));if(p>=1&&p<=100000000&&out.indexOf(p)<0)out.push(p);}return out;};" +
                "var attr=function(e,n){return e&&e.getAttribute?e.getAttribute(n)||'':'';};" +
                "var desc=function(e){if(!e)return '';var r=e.getBoundingClientRect(),c=getComputedStyle(e),par=e.parentElement;var prev=e.previousElementSibling,next=e.nextElementSibling;return 'TAG='+e.tagName+' CLASS='+trim(e.className||'',160)+' ID='+attr(e,'id')+' AUTO='+attr(e,'data-auto')+' ZONE='+attr(e,'data-zone-name')+' OFFER='+attr(e,'data-offer-id')+' RECT='+[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)].join(',')+' COLOR='+c.color+' FONT='+c.fontSize+'/'+c.fontWeight+' TEXT='+trim(e.innerText||e.textContent||'',240)+' PARENT='+trim(par?(par.innerText||par.textContent||''):'',300)+' PREV='+trim(prev?(prev.innerText||prev.textContent||''):'',160)+' NEXT='+trim(next?(next.innerText||next.textContent||''):'',160);};" +
                "var find=function(p){var out=[];document.querySelectorAll('*').forEach(function(e){if(!visible(e))return;var t=norm(e.innerText||e.textContent||'');if(t.indexOf(p+' ₽')>=0&&t.length<=500){var key=e.tagName+'|'+attr(e,'data-auto')+'|'+attr(e,'data-offer-id')+'|'+t;if(!out.some(function(x){return x.key===key;}))out.push({key:key,e:e});}});return out;};" +
                "var lines=[];" +
                "['414','531'].forEach(function(p){var arr=find(p);lines.push('=== PRICE '+p+' ₽ : '+arr.length+' elements ===');arr.slice(0,18).forEach(function(x,i){var e=x.e;var p1=e.parentElement,p2=p1&&p1.parentElement,p3=p2&&p2.parentElement;lines.push('#'+i+' '+desc(e));lines.push('  P2='+trim(p2?(p2.innerText||p2.textContent||''):'',400));lines.push('  P3='+trim(p3?(p3.innerText||p3.textContent||''):'',400));});});" +
                "var offers=[];document.querySelectorAll('[data-auto^=offerContainer_]').forEach(function(e,i){if(i<10)offers.push('#'+i+' '+desc(e));});" +
                "lines.push('=== OFFER CONTAINERS ===');lines=lines.concat(offers);" +
                "var uniq={};var all=[];document.querySelectorAll('*').forEach(function(e){if(!visible(e))return;var t=norm(e.innerText||e.textContent||'');if(t.length>500||t.indexOf('₽')<0)return;nums(t).forEach(function(p){var k=p+'|'+attr(e,'data-auto')+'|'+attr(e,'data-offer-id')+'|'+t;if(!uniq[k]){uniq[k]=1;all.push({p:p,t:t,e:e});}});});" +
                "all.sort(function(a,b){return a.p-b.p;});" +
                "lines.push('=== UNIQUE VISIBLE PRICE ELEMENTS (first 120) ===');all.slice(0,120).forEach(function(x,i){lines.push('#'+i+' '+x.p+' ₽ | '+desc(x.e));});" +
                "var body=norm(document.body?document.body.innerText:'');var occ=[];['414','531'].forEach(function(p){var pos=0;while((pos=body.indexOf(p+' ₽',pos))>=0&&occ.length<20){occ.push('BODY '+p+' at '+pos+': '+body.slice(Math.max(0,pos-180),Math.min(body.length,pos+p.length+200)));pos+=p.length+2;}});" +
                "lines.push('=== BODY OCCURRENCES ===');lines=lines.concat(occ);" +
                "return JSON.stringify({diagnostic:lines.join('\\n'),count:all.length});" +
                "}catch(e){return JSON.stringify({diagnostic:'YANDEX_DOM_DIAG_ERROR: '+String(e),count:0})}})()";

        webView.evaluateJavascript(js, value -> {
            yandexDiag = jsonString(unquote(value), "diagnostic");
            yandexState = "{\"noCard\":-1,\"card\":-1,\"title\":\"\"}";
            extract(originalUrl, callback);
        });
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
                    double ycard = jsonNumber(yandexState, "card");
                    if (validPrice(yp)) {
                        result.price = yp;
                        result.noCardPrice = yp;
                    }
                    if (validPrice(ycard)) result.cardPrice = ycard;
                    String yt = jsonString(yandexState, "title");
                    if (!yt.trim().isEmpty() && !yt.equalsIgnoreCase("%og_title%")) result.name = clean(yt);
                    result.diagnostic = yandexDiag + "\nJava Result: noCard=" + result.noCardPrice + ", card=" + result.cardPrice + ", price=" + result.price;
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

    private static boolean jsonBoolean(String s, String key) {
        return Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE)
                .matcher(s == null ? "" : s).find();
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
