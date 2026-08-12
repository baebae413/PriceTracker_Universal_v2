package ru.pricetracker;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    PriceDb db;
    LinearLayout list;
    TextView summary;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    UniversalParser parser;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        db = new PriceDb(this);
        parser = new UniversalParser(this);
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 36, 32, 32);

        TextView title = new TextView(this);
        title.setText("МОНИТОРИНГ ЦЕН");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setPadding(0,0,0,18);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Поддержка: Ozon, Яндекс Маркет, Подружка и другие сайты");
        hint.setTextSize(14);
        root.addView(hint);

        Button add = new Button(this);
        add.setText("＋ ДОБАВИТЬ URL");
        add.setOnClickListener(v -> addUrlDialog());
        root.addView(add);

        Button check = new Button(this);
        check.setText("ПРОВЕРИТЬ ЦЕНЫ");
        check.setOnClickListener(v -> checkAll());
        root.addView(check);

        summary = new TextView(this);
        summary.setTextSize(16);
        summary.setPadding(0, 18, 0, 18);
        root.addView(summary);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void addUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://...");
        input.setSingleLine(false);

        new AlertDialog.Builder(this)
            .setTitle("Добавить товар")
            .setMessage("Вставь ссылку на страницу конкретного товара.")
            .setView(input)
            .setPositiveButton("Добавить", (d,w) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) addUrl(url);
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void addUrl(String url) {
        Toast.makeText(this, "Открываю страницу товара…", Toast.LENGTH_SHORT).show();
        parser.product(url, new UniversalParser.Callback() {
            @Override public void success(UniversalParser.Result r) {
                db.add(url, r.name, r.price, r.oldPrice);
                refresh();
                toast("Добавлено: " + r.name + "\n" + formatPrice(r.price) + " ₽");
            }
            @Override public void error(Exception e) {
                toast("Не удалось получить товар: " + e.getMessage());
            }
        });
    }

    private void checkAll() {
        List<PriceDb.Product> products = db.all();
        if (products.isEmpty()) {
            toast("Сначала добавь товар");
            return;
        }
        summary.setText("Проверяю " + products.size() + " товаров…");
        checkNext(products, 0, 0, 0, 0);
    }

    private void checkNext(List<PriceDb.Product> products, int index,
                           int down, int up, int same) {
        if (index >= products.size()) {
            final int fDown=down, fUp=up, fSame=same;
            runOnUiThread(() -> {
                summary.setText("🟢 Подешевели: " + fDown +
                        "   🔴 Подорожали: " + fUp +
                        "   ⚪ Без изменений: " + fSame);
                refresh();
            });
            return;
        }

        PriceDb.Product p = products.get(index);
        summary.setText("Проверяю " + (index+1) + " из " + products.size() +
                ":\n" + (p.name == null ? p.url : p.name));

        parser.product(p.url, new UniversalParser.Callback() {
            @Override public void success(UniversalParser.Result r) {
                double old = p.lastPrice;
                String status;
                int d=down,u=up,s=same;
                if (r.price < old - 0.001) { status="down"; d++; }
                else if (r.price > old + 0.001) { status="up"; u++; }
                else { status="same"; s++; }
                db.updatePrice(p.id, r.price, status);
                checkNext(products, index+1, d, u, s);
            }
            @Override public void error(Exception e) {
                // Keep the old price when a site cannot be parsed this time.
                checkNext(products, index+1, down, up, same);
            }
        });
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        List<PriceDb.Product> ps = db.all();
        summary.setText("Товаров сохранено: " + ps.size());
        for (PriceDb.Product p : ps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 18, 0, 18);

            TextView name = new TextView(this);
            name.setText((p.name == null || p.name.isEmpty()) ? p.url : p.name);
            name.setTextSize(17);
            name.setTextColor(Color.BLACK);

            TextView site = new TextView(this);
            site.setText(p.url);
            site.setTextSize(11);

            TextView price = new TextView(this);
            String arrow = "";
            if ("down".equals(p.status)) arrow = "  🟢 ↓";
            if ("up".equals(p.status)) arrow = "  🔴 ↑";
            if ("same".equals(p.status)) arrow = "  ⚪ =";
            price.setText(formatPrice(p.lastPrice) + " ₽" + arrow);
            price.setTextSize(16);

            row.addView(name);
            row.addView(price);
            row.addView(site);

            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Удалить товар?")
                    .setMessage(p.name)
                    .setPositiveButton("Удалить", (d,w) -> { db.delete(p.id); refresh(); })
                    .setNegativeButton("Отмена", null).show();
                return true;
            });
            list.addView(row);
        }
    }

    private String formatPrice(double p) {
        if (Math.abs(p - Math.rint(p)) < 0.001)
            return String.format(Locale.US, "%.0f", p);
        return String.format(Locale.US, "%.2f", p);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        if (parser != null) { /* WebView instances are released by parser */ }
        pool.shutdownNow();
        db.close();
        super.onDestroy();
    }
}
