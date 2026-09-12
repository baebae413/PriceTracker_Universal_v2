package ru.pricetracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private PriceDb db;
    private LinearLayout list;
    private TextView summary;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = new PriceDb(this);
        buildUi();
        refresh();
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s == null ? "" : s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 36, 32, 40);

        root.addView(text("Мониторинг цен", 28, Color.BLACK));
        TextView sub = text("Ozon • Яндекс Маркет • Подружка и другие сайты", 14, Color.DKGRAY);
        sub.setPadding(0, 6, 0, 18);
        root.addView(sub);

        Button add = new Button(this);
        add.setText("＋  ДОБАВИТЬ ТОВАР");
        add.setOnClickListener(v -> addUrlDialog());
        root.addView(add);

        Button check = new Button(this);
        check.setText("ПРОВЕРИТЬ СЕЙЧАС");
        check.setOnClickListener(v -> checkAll());
        root.addView(check);

        summary = text("", 14, Color.DKGRAY);
        summary.setPadding(0, 14, 0, 14);
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
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Добавить товар")
                .setMessage("Вставь ссылку на страницу конкретного товара.")
                .setView(input)
                .setPositiveButton("Добавить", (d, w) -> addUrl(input.getText().toString().trim()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void addUrl(String url) {
        if (url.isEmpty()) { toast("Ссылка пустая"); return; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) { toast("Нужна ссылка http:// или https://"); return; }
        summary.setText("Читаю страницу товара…");
        new UniversalParser(this).product(url, new UniversalParser.Callback() {
            @Override public void success(UniversalParser.Result r) {
                if (r.price < 1 || r.price > 100000000) { error(new Exception("Цена не найдена")); return; }
                long id = db.add(url, r.name, r.site, r.price);
                refresh();
                toast(id == -1 ? "Этот товар уже добавлен" : "Добавлено: " + r.name + " — " + formatPrice(r.price) + " ₽");
            }
            @Override public void error(Exception e) { toast("Не удалось прочитать товар: " + e.getMessage()); refresh(); }
        });
    }

    private void checkAll() {
        List<PriceDb.Product> products = db.enabledProducts();
        if (products.isEmpty()) { toast("Нет товаров для проверки"); return; }
        summary.setText("Проверяю 1 из " + products.size() + "…");
        checkNext(products, 0, 0, 0, 0);
    }

    private void checkNext(List<PriceDb.Product> products, int index, int down, int up, int same) {
        if (index >= products.size()) {
            summary.setText("🟢 Подешевели: " + down + "   🔴 Подорожали: " + up + "   ⚪ Без изменений: " + same);
            refresh();
            return;
        }
        PriceDb.Product p = products.get(index);
        summary.setText("Проверяю " + (index + 1) + " из " + products.size() + ":\n" + p.name);
        new UniversalParser(this).product(p.url, new UniversalParser.Callback() {
            @Override public void success(UniversalParser.Result r) {
                double old = p.lastPrice;
                String status = r.price < old - 0.001 ? "down" : (r.price > old + 0.001 ? "up" : "same");
                db.updatePrice(p.id, r.price, status);
                checkNext(products, index + 1,
                        down + ("down".equals(status) ? 1 : 0),
                        up + ("up".equals(status) ? 1 : 0),
                        same + ("same".equals(status) ? 1 : 0));
            }
            @Override public void error(Exception e) { checkNext(products, index + 1, down, up, same); }
        });
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        List<PriceDb.Product> products = db.all();
        summary.setText("Товаров сохранено: " + products.size());
        for (PriceDb.Product p : products) createProductView(p);
    }

    private void createProductView(PriceDb.Product p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 18, 0, 18);

        TextView name = text(p.name, 17, Color.BLACK);
        name.setOnClickListener(v -> openUrl(p.url));
        card.addView(name);

        String icon = "";
        if ("down".equals(p.status)) icon = "  🟢 ↓";
        else if ("up".equals(p.status)) icon = "  🔴 ↑";
        else if ("same".equals(p.status)) icon = "  ⚪ =";
        card.addView(text(formatPrice(p.lastPrice) + " ₽" + icon, 18, Color.BLACK));

        TextView site = text(p.site, 12, Color.rgb(70, 70, 150));
        site.setPaintFlags(site.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        site.setOnClickListener(v -> openUrl(p.url));
        card.addView(site);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Switch enabled = new Switch(this);
        enabled.setText("Отслеживать");
        enabled.setChecked(p.enabled);
        enabled.setOnCheckedChangeListener((b, checked) -> db.setEnabled(p.id, checked));
        controls.addView(enabled, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button history = new Button(this);
        history.setText("История");
        controls.addView(history);

        Button delete = new Button(this);
        delete.setText("Удалить");
        delete.setOnClickListener(v -> confirmDelete(p));
        controls.addView(delete);
        card.addView(controls);

        LinearLayout historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setVisibility(View.GONE);
        history.setOnClickListener(v -> {
            if (historyBox.getVisibility() == View.GONE) {
                showHistory(p, historyBox);
                historyBox.setVisibility(View.VISIBLE);
                history.setText("Скрыть");
            } else {
                historyBox.setVisibility(View.GONE);
                history.setText("История");
            }
        });
        card.addView(historyBox);
        list.addView(card);
    }

    private void showHistory(PriceDb.Product p, LinearLayout box) {
        box.removeAllViews();
        List<PriceDb.History> history = db.history(p.id);
        if (history.isEmpty()) { box.addView(text("История пуста", 14, Color.DKGRAY)); return; }
        box.addView(text("Минимум: " + formatPrice(db.minPrice(p.id)) + " ₽   Максимум: " + formatPrice(db.maxPrice(p.id)) + " ₽", 14, Color.DKGRAY));
        for (PriceDb.History h : history) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = text(formatDate(h.checkedAt) + "   " + formatPrice(h.price) + " ₽", 14, Color.DKGRAY);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText("🗑");
            del.setOnClickListener(v -> { db.deleteHistory(h.id); showHistory(p, box); });
            row.addView(del);
            box.addView(row);
        }
        Button clear = new Button(this);
        clear.setText("ОЧИСТИТЬ ИСТОРИЮ");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Очистить историю?")
                .setPositiveButton("Очистить", (d,w) -> { db.clearHistory(p.id); showHistory(p, box); })
                .setNegativeButton("Отмена", null).show());
        box.addView(clear);
    }

    private void confirmDelete(PriceDb.Product p) {
        new AlertDialog.Builder(this).setTitle("Удалить товар?").setMessage(p.name)
                .setPositiveButton("Удалить", (d,w) -> { db.delete(p.id); refresh(); })
                .setNegativeButton("Отмена", null).show();
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("Не удалось открыть ссылку"); }
    }

    private String formatPrice(double p) {
        return Math.abs(p - Math.rint(p)) < 0.001 ? String.format(Locale.US, "%.0f", p) : String.format(Locale.US, "%.2f", p);
    }

    private String formatDate(long time) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
