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

        String js = "(function(){try{" +
                "var trim=function(s,n){s=String(s||'').replace(/\\s+/g,' ').trim();return s.length>n?s.slice(0,n)+'…':s;};" +
                "var priceRe=/(?:^|[^0-9])(\\d{1,3}(?:[\\s\\u00a0\\u202f]\\d{3})+|\\d{2,7})\\s*₽/g;" +
                "var num=function(s){var m=String(s||'').match(/(\\d{1,3}(?:[\\s\\u00a0\\u202f]\\d{3})+|\\d{2,7})\\s*₽/);return m?Number(m[1].replace(/[\\s\\u00a0\\u202f]/g,'')):-1;};" +
                "var visible=function(e){if(!e||!e.getBoundingClientRect)return false;var r=e.getBoundingClientRect(),c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&c.opacity!=='0'&&r.width>0&&r.height>0;};" +
                "var norm=function(s){return String(s||'').toLowerCase().replace(/ё/g,'е').replace(/\\s+/g,' ').trim();};" +
                "var candidates=[];var seen={};" +
                "var add=function(e){if(!e||!visible(e))return;var t=String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim();if(!t||t.length>220||t.indexOf('₽')<0)return;" +
                "var ms=t.match(priceRe)||[];if(!ms.length)return;var p=num(t);if(p<1)return;" +
                "var par=e.parentElement,a1=par&&par.parentElement,a2=a1&&a1.parentElement;" +
                "var ctx=norm(t+' '+(par?par.innerText||'':'')+' '+(a1?a1.innerText||'':'')+' '+(a2?a2.innerText||'':''));" +
                "var attrs='';for(var k=0;k<e.attributes.length;k++){var at=e.attributes[k];if(at.name.indexOf('data-')===0)attrs+=' '+at.name+'='+at.value;}" +
                "var auto=e.getAttribute('data-auto')||'';var zone=e.getAttribute('data-zone-name')||'';var cls=typeof e.className==='string'?e.className:'';" +
                "var scoreCard=0,scoreNo=0;" +
                "if(/пэй|яндекс пэй|с картой|зелен(ая|ая) цена|green price/.test(ctx))scoreCard+=8;" +
                "if(/без карты|обычная цена|цена без карты/.test(ctx))scoreNo+=8;" +
                "if(auto==='snippet-price-current')scoreCard+=3;" +
                "if(auto==='snippet-price-old')scoreCard-=7;" +
                "if(auto==='snippet-price-current' || /ds-valueLine/.test(cls))scoreCard+=1;" +
                "if(/пэй/.test(ctx))scoreCard+=3;" +
                "if(/доставка|промокод|скидк|заказ от|экспресс|маркет 11 окт/.test(ctx))scoreCard-=2;" +
                "if(/cia-vs|cia-cs/.test(cls)&&/пэй/.test(ctx))scoreCard+=2;" +
                "var key=p+'|'+t+'|'+auto+'|'+zone+'|'+cls;if(seen[key])return;seen[key]=1;" +
                "candidates.push({p:p,t:t,ctx:ctx,tag:e.tagName,auto:auto,zone:zone,cls:cls,card:scoreCard,no:scoreNo,top:e.getBoundingClientRect().top,left:e.getBoundingClientRect().left});};" +
                "var els=document.querySelectorAll('*');for(var i=0;i<els.length;i++){var e=els[i],t=e.innerText||e.textContent||'';if(t.indexOf('₽')>=0&&t.length<=220)add(e);}" +
                "var card=-1,noCard=-1,cardScore=-999,noScore=-999;" +
                "for(var j=0;j<candidates.length;j++){var x=candidates[j];if(x.card>cardScore&&x.p>=1){cardScore=x.card;card=x.p;}if(x.no>noScore&&x.p>=1){noScore=x.no;noCard=x.p;}}" +
                "if(card<1){for(var j=0;j<candidates.length;j++){var x=candidates[j];if(x.auto==='snippet-price-current'&&x.p>=1&&/пэй/.test(x.ctx)){card=x.p;break;}}}" +
                "if(noCard<1){for(var j=0;j<candidates.length;j++){var x=candidates[j];if(/без карты|обычная цена/.test(x.ctx)&&x.p>=1){noCard=x.p;break;}}}" +
                "if(noCard<1){var bestNo=-999;for(var j=0;j<candidates.length;j++){var x=candidates[j];var sc=0;if(x.auto==='snippet-price-current')sc+=6;if(/ds-valueLine/.test(x.cls))sc+=2;if(/пэй/.test(x.ctx))sc-=6;if(/доставка|промокод|заказ от|экспресс|скидк/.test(x.ctx))sc-=6;if(x.p===card)sc-=20;if(/snippet-price-old/.test(x.auto))sc-=20;if(sc>bestNo&&x.p>=1){bestNo=sc;noCard=x.p;}}}" +
                "var lines=[];for(var j=0;j<candidates.length&&lines.length<30;j++){var x=candidates[j];if(x.p===card||x.p===noCard||x.card>=6||x.no>=6)lines.push(x.p+' ₽ | cardScore='+x.card+' noCardScore='+x.no+' | '+x.t+' | ctx='+trim(x.ctx,300)+' | auto='+x.auto+' | zone='+x.zone);}" +
                "return JSON.stringify({card:card,noCard:noCard,cardScore:cardScore,noScore:noScore,count:candidates.length,details:lines.join('\\n'),body:trim(document.body?document.body.innerText:'',1800),title:(document.querySelector('[data-auto=productCardTitle],h1')||{}).innerText||''});" +
                "}catch(e){return JSON.stringify({card:-1,noCard:-1,count:0,details:'YANDEX_DOM_ERROR: '+String(e),body:'',title:''})}})()";

        webView.evaluateJavascript(js, value -> {
            yandexState = unquote(value);
            double noCard = jsonNumber(yandexState, "noCard");
            double card = jsonNumber(yandexState, "card");
            String title = jsonString(yandexState, "title");
            yandexDiag = "Yandex DOM parser: candidates=" + jsonNumber(yandexState, "count") +
                    ", card=" + (validPrice(card) ? String.valueOf((long)card) : "нет") +
                    ", noCard=" + (validPrice(noCard) ? String.valueOf((long)noCard) : "нет") +
                    ", cardScore=" + jsonNumber(yandexState, "cardScore") +
                    ", noCardScore=" + jsonNumber(yandexState, "noScore");
            String details = jsonString(yandexState, "details");
            if (!details.isEmpty()) yandexDiag += "\n" + details;
            if (!validPrice(card) || !validPrice(noCard)) {
                String body = jsonString(yandexState, "body");
                if (!body.isEmpty()) yandexDiag += "\nBODY:\n" + body;
            }
            yandexState = "{\"noCard\":" + (validPrice(noCard) ? noCard : -1) +
                    ",\"card\":" + (validPrice(card) ? card : -1) +
                    ",\"title\":\"\"}";
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
