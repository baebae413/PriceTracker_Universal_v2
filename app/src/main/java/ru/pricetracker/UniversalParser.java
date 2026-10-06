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
    private int yandexAttempts;

    public UniversalParser(Context context) { this.context = context; }

    @SuppressLint("SetJavaScriptEnabled")
    public void product(String url, Callback callback) {
        main.post(() -> {
            if (busy) { callback.error(new Exception("Парсер занят")); return; }
            busy = true;
            yandexAttempts = 0;
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
            // Yandex approach based on the cpa-offer/data-zone-data structure:
            // one offer contains ordinary/discounted price and greenPrice (Ya-Card).
            // We poll briefly, but never block product addition indefinitely.
            String js = "(function(){try{" +
                    "var out={offerFound:false,offerData:'',noCard:-1,card:-1,title:'',discount:-1,visualNoCard:-1,visualCard:-1};" +
                    "var titleEl=document.querySelector('[data-auto=\"productCardTitle\"], h1');" +
                    "out.title=titleEl?(titleEl.innerText||titleEl.textContent||''):'';" +
                    "var offer=document.querySelector('[data-zone-name=\"cpa-offer\"]');" +
                    "var raw=offer?offer.getAttribute('data-zone-data')||'':'';" +
                    "if(offer){out.offerFound=true;out.offerData=raw.slice(0,180000);}" +
                    "var root=null;try{root=raw?JSON.parse(raw):null;}catch(e){}" +
                    "var first=function(v){if(v==null)return -1;if(typeof v==='number')return isFinite(v)&&v>=1?v:-1;if(typeof v==='string'){var n=Number(v.replace(' ',''));return isFinite(n)&&n>=1?n:-1;}if(typeof v==='object'){if(v.value!=null){var n=first(v.value);if(n>=1)return n;}if(v.price!=null){var n=first(v.price);if(n>=1)return n;}}return -1;};" +
                    "var findKey=function(obj,keys,depth){if(!obj||depth>12)return -1;if(Array.isArray(obj)){for(var i=0;i<obj.length;i++){var n=findKey(obj[i],keys,depth+1);if(n>=1)return n;}return -1;}if(typeof obj!=='object')return -1;" +
                    "for(var k in obj){if(!Object.prototype.hasOwnProperty.call(obj,k))continue;var kl=String(k).toLowerCase();if(keys.indexOf(kl)>=0){var n=first(obj[k]);if(n>=1)return n;}var child=obj[k];if(child&&typeof child==='object'){var n=findKey(child,keys,depth+1);if(n>=1)return n;}}return -1;};" +
                    "if(root){" +
                    " var dp=findKey(root,['discountedprice'],0); var pv=findKey(root,['price'],0); var gp=findKey(root,['greenprice','green_price','cardprice','card_price'],0);" +
                    " out.noCard=pv>=1?pv:-1; out.card=gp>=1?gp:(dp>=1?dp:-1);" +
                    "}" +
                    "var priceNum=function(s){var z=String(s||'').split('₽')[0],x='';for(var j=0;j<z.length;j++){var c=z.charCodeAt(j);if(c>=48&&c<=57)x+=z.charAt(j);}return x?Number(x):-1;};var clickNoCard=function(){var es=document.querySelectorAll('button,span,div');for(var i=0;i<es.length;i++){var t=(es[i].innerText||'').trim().toLowerCase();if(t==='без карты'||t.indexOf('цена без карты')>=0||t.indexOf('обычная цена')>=0){try{es[i].click();}catch(e){}break;}}};clickNoCard();var els=document.querySelectorAll('*');for(var i=0;i<els.length;i++){var e=els[i],t=(e.innerText||'').trim();if(!t||t.length>80||t.indexOf('₽')<0)continue;var p=priceNum(t);if(p<1)continue;var col=getComputedStyle(e).color||'';var parts=col.replace('rgba(','').replace('rgb(','').replace(')','').split(',');if(parts.length>=3){var rr=+parts[0],gg=+parts[1],bb=+parts[2];if(gg>rr*1.25&&gg>bb*1.15&&gg>80){if(out.visualCard<1)out.visualCard=p;}else if(Math.max(rr,gg,bb)-Math.min(rr,gg,bb)<45&&rr<180){if(out.visualNoCard<1)out.visualNoCard=p;}}}if(out.visualNoCard>=1)out.noCard=out.visualNoCard;if(out.visualCard>=1)out.card=out.visualCard;var body=document.body?document.body.innerText:'';" +
                    "var cardMatch=body.match(/([0-9]{1,3}(?:[\\s\\u00a0\\u202f][0-9]{3})+|[0-9]{2,7})\\s*₽[^\\n]{0,80}(?:карт|Яндекс|плюс)/i);" +
                    "var all=body.match(/([0-9]{1,3}(?:[\\s\\u00a0\\u202f][0-9]{3})+|[0-9]{2,7})\\s*₽/g)||[];" +
                    "if(out.card<1&&cardMatch)out.card=Number(cardMatch[1].replace(/[\\s\\u00a0\\u202f]/g,''));" +
                    "if(out.noCard<1&&all.length)out.noCard=Number(all[0].replace(/[^0-9]/g,''));" +
                    "return JSON.stringify(out);" +
                    "}catch(e){return JSON.stringify({error:String(e),noCard:-1,card:-1,title:''})}})()";
            webView.evaluateJavascript(js, value -> {
                yandexState = unquote(value);
                yandexAttempts++;
                boolean ready = jsonNumber(yandexState, "noCard") >= 1
                        || jsonNumber(yandexState, "card") >= 1
                        || !jsonString(yandexState, "title").trim().isEmpty();
                if (ready || yandexAttempts >= 6) {
                    main.postDelayed(() -> extract(originalUrl, callback), 250);
                } else {
                    main.postDelayed(() -> prepareAndExtract(originalUrl, callback), 900);
                }
            });
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
                    double ycard = jsonNumber(yandexState, "card");
                    if (validPrice(yp)) {
                        result.noCardPrice = yp;
                        result.price = yp;
                    }
                    if (validPrice(ycard)) result.cardPrice = ycard;
                    String yt = jsonString(yandexState, "title");
                    if (!yt.trim().isEmpty() && !yt.equalsIgnoreCase("%og_title%")) result.name = clean(yt);
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
