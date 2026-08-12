package ru.pricetracker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;

import java.util.*;
import java.util.regex.*;

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
                    "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            );


            webView.setWebViewClient(new WebViewClient() {

                @Override
                public void onPageFinished(WebView view, String loadedUrl) {

                    main.postDelayed(
                            () -> extract(url, callback),
                            5000
                    );
                }


                @Override
                public void onReceivedError(
                        WebView view,
                        int errorCode,
                        String description,
                        String failingUrl
                ) {

                    finish();

                    callback.error(
                            new Exception(
                                    "Ошибка загрузки: " + description
                            )
                    );
                }

            });


            webView.loadUrl(url);


            main.postDelayed(() -> {

                if (busy) {

                    finish();

                    callback.error(
                            new Exception(
                                    "Страница загружается слишком долго"
                            )
                    );
                }

            }, 20000);

        });
    }



    private void extract(String originalUrl, Callback callback) {

        if (!busy || webView == null)
            return;


        String js =
                "(function(){return JSON.stringify({" +
                        "title:document.title," +
                        "text:(document.body?document.body.innerText:'')," +
                        "html:(document.documentElement?document.documentElement.outerHTML:'')" +
                        "});})()";


        webView.evaluateJavascript(js, value -> {

            try {

                String raw = unquote(value);

                Result r = parse(raw, originalUrl);


                if (r.price < 0)
                    throw new Exception("Цена не найдена");


                if (r.name == null || r.name.trim().isEmpty())
                    r.name = domain(originalUrl);


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


        List<Double> jsonPrices = new ArrayList<>();


        Matcher pm = Pattern.compile(
                "\"price\"\\s*:\\s*\"?([0-9]+(?:[.,][0-9]+)?)\"?",
                Pattern.CASE_INSENSITIVE
        ).matcher(html);


        while (pm.find()) {

            double p = num(pm.group(1));

            if (p > 0)
                jsonPrices.add(p);
        }



        Matcher nm = Pattern.compile(
                "\"name\"\\s*:\\s*\"([^\"]{2,300})\"",
                Pattern.CASE_INSENSITIVE
        ).matcher(html);


        if (nm.find())
            r.name = decode(nm.group(1));
                ArrayList<Double> candidates = new ArrayList<>();

        addPrices(text, candidates);

        String cleanHtml = html
                .replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ");

        addPrices(cleanHtml, candidates);

        candidates.addAll(jsonPrices);


        candidates.removeIf(v ->
                v < 1 || v > 100000000
        );


        LinkedHashSet<Long> uniqueSet = new LinkedHashSet<>();
        ArrayList<Double> unique = new ArrayList<>();

        for (Double v : candidates) {

            long value = Math.round(v);

            if (uniqueSet.add(value)) {
                unique.add(v);
            }

        }


        // Приоритет нормальной цены из JSON-LD
        if (!jsonPrices.isEmpty()) {

            for (Double v : jsonPrices) {

                if (v >= 50 && v <= 100000000) {
                    r.price = v;
                    break;
                }

            }

        }


        if (r.price < 0 && !unique.isEmpty()) {

            r.price = choosePrice(unique, text);

        }


        // Поиск старой цены
        if (r.price > 0) {

            for (Double v : unique) {

                if (v > r.price &&
                        v <= r.price * 5) {

                    if (r.oldPrice < 0 ||
                            v < r.oldPrice) {

                        r.oldPrice = v;

                    }

                }

            }

        }


        if (r.name == null || r.name.isEmpty()) {

            r.name = title
                    .replaceAll("\\s*[|–-].*$", "")
                    .trim();

        }


        return r;
    }


    private static double choosePrice(List<Double> list, String text) {

        String lower = text.toLowerCase(Locale.ROOT);


        for (Double v : list) {

            String value = String.valueOf((long)Math.round(v));

            int pos = lower.indexOf(value);

            if (pos >= 0) {

                int end = Math.min(
                        lower.length(),
                        pos + 100
                );

                String near = lower.substring(pos, end);


                if (near.contains("₽") ||
                        near.contains("руб") ||
                        near.contains("цена")) {

                    return v;

                }

            }

        }


        for (Double v : list) {

            if (v >= 50) {
                return v;
            }

        }


        return list.get(0);

    }
        private static void addPrices(String s, List<Double> out) {

        if (s == null) return;


        // Основной поиск цены с валютой:
        // 2756 ₽
        // 2 756 ₽
        // 2.756 ₽
        // 2756 руб
        Matcher m = Pattern.compile(
                "(?<!\\d)(\\d{1,3}(?:[\\s.]\\d{3})+|\\d+)\\s*(?:₽|руб\\.?|RUB)",
                Pattern.CASE_INSENSITIVE
        ).matcher(s);


        while (m.find() && out.size() < 200) {

            String x = m.group(1)
                    .replace(" ", "")
                    .replace(".", "");


            out.add(num(x));

        }



        // Цена рядом со словами цена / price
        Matcher m2 = Pattern.compile(
                "(?:цена|price|salePrice|currentPrice)"
                + "[^0-9]{0,100}"
                + "(\\d{1,3}(?:[\\s.]\\d{3})+|\\d+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(s);



        while (m2.find() && out.size() < 200) {

            String x = m2.group(1)
                    .replace(" ", "")
                    .replace(".", "");


            out.add(num(x));

        }

    }



    private static double num(String s) {

        try {

            return Double.parseDouble(
                    s.replace(" ", "")
                     .replace(",", ".")
            );

        } catch (Exception e) {

            return -1;

        }

    }



    private static String field(String json, String key) {

        Matcher m = Pattern.compile(
                "\"" + key +
                "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                Pattern.DOTALL
        ).matcher(json);


        return m.find()
                ? decode(m.group(1))
                : "";

    }



    private static String unquote(String s) {

        if (s == null) return "";


        if (s.startsWith("\"") &&
                s.endsWith("\"")) {

            s = s.substring(
                    1,
                    s.length() - 1
            );


            s = s.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");

        }


        return s;

    }



    private static String decode(String s) {

        if (s == null) return "";


        return s.replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0022", "\"")
                .replace("\\\\\"", "\"");

    }



    private static String domain(String url) {

        try {

            return new java.net.URL(url)
                    .getHost();

        } catch (Exception e) {

            return url;

        }

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
