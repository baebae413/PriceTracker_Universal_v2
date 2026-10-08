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

        // Diagnostic only: inspect the real control next to the current Yandex Pay price.
        // We do not classify prices or write them to the DB in this test.
        String js = "(function(){try{" +
                "var norm=function(s){return String(s||'').replace(/\\s+/g,' ').trim();};" +
                "var trim=function(s,n){s=norm(s);return s.length>n?s.slice(0,n)+'…':s;};" +
                "var attr=function(e,n){return e&&e.getAttribute?e.getAttribute(n)||'':'';};" +
                "var visible=function(e){if(!e||!e.getBoundingClientRect)return false;var r=e.getBoundingClientRect(),c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&c.opacity!=='0'&&r.width>0&&r.height>0;};" +
                "var short=function(e){if(!e)return '';var r=e.getBoundingClientRect();return 'TAG='+e.tagName+' CLASS='+trim(e.className||'',100)+' AUTO='+attr(e,'data-auto')+' ROLE='+attr(e,'role')+' ARIAEXP='+attr(e,'aria-expanded')+' ARIALABEL='+attr(e,'aria-label')+' TITLE='+attr(e,'title')+' RECT='+[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)].join(',')+' TEXT='+trim(e.innerText||e.textContent||'',180)+' HTML='+trim(e.outerHTML||'',500);};" +
                "var price=document.querySelector('[data-auto=\\"snippet-price-current\\"]');" +
                "var lines=['=== PRICE CONTROL DIAGNOSTIC ==='];" +
                "lines.push('CURRENT PRICE ELEMENT: '+(price?short(price):'NOT FOUND'));" +
                "var root=price?price.parentElement:null;" +
                "for(var level=0;root&&level<5;level++,root=root.parentElement){" +
                " lines.push('--- ANCESTOR '+level+' ---');" +
                " lines.push(short(root));" +
                " var els=root.querySelectorAll('button,[role=button],[aria-expanded],a,svg');" +
                " var seen=[];" +
                " for(var i=0;i<els.length&&i<20;i++){var e=els[i];if(!visible(e))continue;var s=short(e);if(seen.indexOf(s)>=0)continue;seen.push(s);lines.push('CONTROL '+i+': '+s);}" +
                "}" +
                "var all=[];document.querySelectorAll('button,[role=button],[aria-expanded],a').forEach(function(e){if(!visible(e))return;var r=e.getBoundingClientRect();if(price){var pr=price.getBoundingClientRect();if(Math.abs(r.top-pr.top)<100&&Math.abs(r.left-pr.left)<180){all.push(e);}}});" +
                "lines.push('=== NEARBY CONTROLS ===');" +
                "all.slice(0,30).forEach(function(e,i){lines.push('#'+i+' '+short(e));});" +
                "var target=null;" +
                "for(var i=0;i<all.length;i++){var e=all[i],t=norm(e.innerText||e.textContent||'').toLowerCase(),al=(attr(e,'aria-label')+' '+attr(e,'title')).toLowerCase();if(!/414|пэй|price|цена/.test(t+' '+al)){target=e;break;}}" +
                "var clicked='NONE';" +
                "if(target){clicked=short(target);try{target.click();}catch(x){try{target.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(y){}}}" +
                "return JSON.stringify({before:lines.join('\\n'),clicked:clicked});" +
                "}catch(e){return JSON.stringify({before:'DIAG ERROR: '+String(e),clicked:'ERROR'})}})()";

        webView.evaluateJavascript(js, value -> {
            String raw = unquote(value);
            String before = jsonString(raw, "before");
            String clicked = jsonString(raw, "clicked");
            main.postDelayed(() -> {
                String afterJs = "(function(){try{" +
                        "var norm=function(s){return String(s||'').replace(/\\s+/g,' ').trim();};" +
                        "var trim=function(s,n){s=norm(s);return s.length>n?s.slice(0,n)+'…':s;};" +
                        "var attr=function(e,n){return e&&e.getAttribute?e.getAttribute(n)||'':'';};" +
                        "var visible=function(e){if(!e||!e.getBoundingClientRect)return false;var r=e.getBoundingClientRect(),c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&c.opacity!=='0'&&r.width>0&&r.height>0;};" +
                        "var short=function(e){if(!e)return '';var r=e.getBoundingClientRect();return 'TAG='+e.tagName+' CLASS='+trim(e.className||'',100)+' AUTO='+attr(e,'data-auto')+' ROLE='+attr(e,'role')+' ARIAEXP='+attr(e,'aria-expanded')+' ARIALABEL='+attr(e,'aria-label')+' TITLE='+attr(e,'title')+' RECT='+[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)].join(',')+' TEXT='+trim(e.innerText||e.textContent||'',180);};" +
                        "var lines=['=== AFTER CONTROL CLICK ==='];" +
                        "var prices=[];document.querySelectorAll('[data-auto=\\"snippet-price-current\\"], [data-auto=\\"snippet-price-old\\"], [data-auto*=\\"price\\"]').forEach(function(e){if(visible(e))prices.push(short(e));});" +
                        "lines.push('VISIBLE PRICE ELEMENTS: '+prices.length);prices.slice(0,30).forEach(function(e,i){lines.push('#'+i+' '+e);});" +
                        "var body=norm(document.body?document.body.innerText:'');lines.push('BODY HAS ПЭЙ='+body.toLowerCase().indexOf('пэй')>=0+' BODY HAS БЕЗ КАРТЫ='+body.toLowerCase().indexOf('без карты')>=0);" +
                        "var pos=0,n=0;while((pos=body.indexOf('₽',pos))>=0&&n<25){lines.push('RUBLE '+n+' at '+pos+': '+body.slice(Math.max(0,pos-100),Math.min(body.length,pos+140)));pos++;n++;}" +
                        "return JSON.stringify({after:lines.join('\\n')});" +
                        "}catch(e){return JSON.stringify({after:'AFTER DIAG ERROR: '+String(e)})}})()";
                webView.evaluateJavascript(afterJs, value2 -> {
                    String after=jsonString(unquote(value2),"after");
                    yandexDiag=before+"\n=== CLICKED CONTROL ===\n"+clicked+"\n"+after;
                    yandexState="{\"noCard\":-1,\"card\":-1,\"title\":\"\"}";
                    extract(originalUrl, callback);
                });
            },1500);
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
                if (result.price < 0) throw new Exception(isYandexMarket(originalUrl) && !yandexDiag.isEmpty() ? yandexDiag : "Цена товара не найдена");
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