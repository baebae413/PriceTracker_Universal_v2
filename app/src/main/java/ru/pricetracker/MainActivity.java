package ru.pricetracker;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.*;
import android.widget.*;
import android.content.Intent;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {

    PriceDb db;
    LinearLayout list;
    TextView summary;

    ExecutorService pool = Executors.newSingleThreadExecutor();
    UniversalParser parser;

    @Override
    public void onCreate(Bundle b) {
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
        title.setPadding(0, 0, 0, 18);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(
                "Поддержка: Ozon, Яндекс Маркет, Подружка и другие сайты"
        );
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
                .setMessage(
                        "Вставь ссылку на страницу конкретного товара."
                )
                .setView(input)
                .setPositiveButton("Добавить", (d, w) -> {

                    String url = input.getText()
                            .toString()
                            .trim();

                    if (!url.isEmpty()) {
                        addUrl(url);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void addUrl(String url) {

        Toast.makeText(
                this,
                "Открываю страницу товара…",
                Toast.LENGTH_SHORT
        ).show();

        parser.product(url, new UniversalParser.Callback() {

            @Override
            public void success(UniversalParser.Result r) {

                db.add(
                        url,
                        r.name,
                        r.price,
                        r.oldPrice
                );

                refresh();

                toast(
                        "Добавлено: " +
                        r.name +
                        "\n" +
                        formatPrice(r.price) +
                        " ₽"
                );
            }

            @Override
            public void error(Exception e) {

                toast(
                        "Не удалось получить товар: " +
                        e.getMessage()
                );
            }
        });
    }

    private void checkAll() {

        List<PriceDb.Product> products = db.all();

        if (products.isEmpty()) {
            toast("Сначала добавь товар");
            return;
        }

        summary.setText(
                "Проверяю " +
                products.size() +
                " товаров…"
        );

        checkNext(
                products,
                0,
                0,
                0,
                0
        );
    }

    private void checkNext(
            List<PriceDb.Product> products,
            int index,
            int down,
            int up,
            int same) {

        if (index >= products.size()) {

            final int fDown = down;
            final int fUp = up;
            final int fSame = same;

            runOnUiThread(() -> {

                summary.setText(
                        "🟢 Подешевели: " +
                        fDown +
                        "   🔴 Подорожали: " +
                        fUp +
                        "   ⚪ Без изменений: " +
                        fSame
                );

                refresh();
            });

            return;
        }

        PriceDb.Product p = products.get(index);

        summary.setText(
                "Проверяю " +
                (index + 1) +
                " из " +
                products.size() +
                ":\n" +
                (p.name == null ? p.url : p.name)
        );

        parser.product(
                p.url,
                new UniversalParser.Callback() {

                    @Override
                    public void success(
                            UniversalParser.Result r) {

                        double old = p.lastPrice;

                        String status;

                        int d = down;
                        int u = up;
                        int s = same;

                        if (r.price < old - 0.001) {

                            status = "down";
                            d++;

                        } else if (r.price > old + 0.001) {

                            status = "up";
                            u++;

                        } else {

                            status = "same";
                            s++;
                        }

                        /*
                         * PriceDb сам решает,
                         * нужно ли добавлять новую запись
                         * в историю.
                         *
                         * Если цена не изменилась,
                         * одинаковая запись второй раз
                         * не появится.
                         */
                        db.updatePrice(
                                p.id,
                                r.price,
                                status
                        );

                        checkNext(
                                products,
                                index + 1,
                                d,
                                u,
                                s
                        );
                    }

                    @Override
                    public void error(Exception e) {

                        // Если сайт временно не удалось прочитать,
                        // старую цену не меняем.

                        checkNext(
                                products,
                                index + 1,
                                down,
                                up,
                                same
                        );
                    }
                }
        );
    }

    private void refresh() {

        if (list == null) {
            return;
        }

        list.removeAllViews();

        List<PriceDb.Product> products = db.all();

        summary.setText(
                "Товаров сохранено: " +
                products.size()
        );

        for (PriceDb.Product p : products) {

            createProductView(p);
        }
    }

    private void createProductView(PriceDb.Product p) {

        LinearLayout row = new LinearLayout(this);

        row.setOrientation(
                LinearLayout.VERTICAL
        );

        row.setPadding(
                0,
                18,
                0,
                18
        );

        /*
         * Название товара
         */
        TextView name = new TextView(this);

        name.setText(
                (p.name == null || p.name.isEmpty())
                        ? p.url
                        : p.name
        );

        name.setTextSize(17);
        name.setTextColor(Color.BLACK);

        /*
         * Нажатие на название тоже открывает товар.
         */
        name.setOnClickListener(v -> openUrl(p.url));

        /*
         * Текущая цена
         */
        TextView price = new TextView(this);

        String arrow = "";

        if ("down".equals(p.status)) {
            arrow = "  🟢 ↓";
        }

        if ("up".equals(p.status)) {
            arrow = "  🔴 ↑";
        }

        if ("same".equals(p.status)) {
            arrow = "  ⚪ =";
        }

        price.setText(
                formatPrice(p.lastPrice) +
                " ₽" +
                arrow
        );

        price.setTextSize(16);

        /*
         * Ссылка
         */
        TextView site = new TextView(this);

        site.setText(p.url);
        site.setTextSize(11);
        site.setTextColor(Color.BLUE);

        site.setPaintFlags(
                site.getPaintFlags() |
                Paint.UNDERLINE_TEXT_FLAG
        );

        site.setOnClickListener(
                v -> openUrl(p.url)
        );
        
        site.setOnLongClickListener(v -> true);

        /*
         * Кнопка истории
         */
        Button historyButton = new Button(this);

        historyButton.setText(
                "История цены ▼"
        );

        /*
         * Контейнер истории.
         * Изначально скрыт.
         */
        LinearLayout historyContainer =
                new LinearLayout(this);

        historyContainer.setOrientation(
                LinearLayout.VERTICAL
        );

        historyContainer.setVisibility(
                View.GONE
        );

        /*
         * Нажатие на "История цены".
         */
        historyButton.setOnClickListener(v -> {

            if (historyContainer.getVisibility()
                    == View.GONE) {

                showHistory(
                        p,
                        historyContainer
                );

                historyContainer.setVisibility(
                        View.VISIBLE
                );

                historyButton.setText(
                        "История цены ▲"
                );

            } else {

                historyContainer.setVisibility(
                        View.GONE
                );

                historyButton.setText(
                        "История цены ▼"
                );
            }
        });

        /*
         * Добавляем элементы карточки.
         */
        row.addView(name);
        row.addView(price);
        row.addView(site);
        row.addView(historyButton);
        row.addView(historyContainer);

        /*
         * Долгое нажатие по товару —
         * удаление товара целиком.
         */
        row.setOnLongClickListener(v -> {

            new AlertDialog.Builder(this)

                    .setTitle("Удалить товар?")

                    .setMessage(
                            p.name == null
                                    ? p.url
                                    : p.name
                    )

                    .setPositiveButton(
                            "Удалить",
                            (d, w) -> {

                                db.delete(p.id);
                                refresh();
                            }
                    )

                    .setNegativeButton(
                            "Отмена",
                            null
                    )

                    .show();

            return true;
        });

        list.addView(row);
    }

    private void showHistory(
            PriceDb.Product product,
            LinearLayout container) {

        container.removeAllViews();

        List<PriceDb.History> history =
                db.history(product.id);

        if (history.isEmpty()) {

            TextView empty = new TextView(this);

            empty.setText(
                    "История цены пока пуста."
            );

            empty.setTextSize(14);

            container.addView(empty);

            return;
        }

        /*
         * Статистика
         */
        double min = db.minPrice(product.id);
        double max = db.maxPrice(product.id);

        TextView statistics = new TextView(this);

        statistics.setText(
                "Минимум: " +
                formatPrice(min) +
                " ₽\n" +
                "Максимум: " +
                formatPrice(max) +
                " ₽"
        );

        statistics.setTextSize(14);

        statistics.setPadding(
                0,
                8,
                0,
                12
        );

        container.addView(statistics);

        /*
         * Последние записи идут первыми.
         */
        for (PriceDb.History h : history) {

            LinearLayout historyRow =
                    new LinearLayout(this);

            historyRow.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            historyRow.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            TextView historyText =
                    new TextView(this);

            historyText.setText(
                    formatDate(h.checkedAt) +
                    "   " +
                    formatPrice(h.price) +
                    " ₽"
            );

            historyText.setTextSize(14);

            LinearLayout.LayoutParams textParams =
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1
                    );

            historyRow.addView(
                    historyText,
                    textParams
            );

            Button deleteButton =
                    new Button(this);

            deleteButton.setText("🗑");

            deleteButton.setOnClickListener(v -> {

                new AlertDialog.Builder(this)

                        .setTitle(
                                "Удалить запись?"
                        )

                        .setMessage(
                                formatDate(h.checkedAt) +
                                "\n" +
                                formatPrice(h.price) +
                                " ₽"
                        )

                        .setPositiveButton(
                                "Удалить",
                                (d, w) -> {

                                    db.deleteHistory(
                                            h.id
                                    );

                                    showHistory(
                                            product,
                                            container
                                    );
                                }
                        )

                        .setNegativeButton(
                                "Отмена",
                                null
                        )

                        .show();
            });

            historyRow.addView(
                    deleteButton
            );

            container.addView(
                    historyRow
            );
        }

        /*
         * Кнопка полной очистки истории.
         */
        Button clearButton =
                new Button(this);

        clearButton.setText(
                "ОЧИСТИТЬ ИСТОРИЮ"
        );

        clearButton.setOnClickListener(v -> {

            new AlertDialog.Builder(this)

                    .setTitle(
                            "Очистить историю?"
                    )

                    .setMessage(
                            "Все сохранённые изменения " +
                            "цены этого товара будут удалены."
                    )

                    .setPositiveButton(
                            "Очистить",
                            (d, w) -> {

                                db.clearHistory(
                                        product.id
                                );

                                showHistory(
                                        product,
                                        container
                                );
                            }
                    )

                    .setNegativeButton(
                            "Отмена",
                            null
                    )

                    .show();
        });

        container.addView(clearButton);
    }

    private String formatDate(long time) {

        SimpleDateFormat format =
                new SimpleDateFormat(
                        "dd.MM.yyyy HH:mm",
                        Locale.getDefault()
                );

        return format.format(
                new Date(time)
        );
    }

    private void openUrl(String url) {

        try {

            Intent intent =
                    new Intent(Intent.ACTION_VIEW);

            intent.setData(
                    Uri.parse(url)
            );

            startActivity(intent);

        } catch (Exception e) {

            toast(
                    "Не удалось открыть ссылку"
            );
        }
        }
    
    private String formatPrice(double p) {

        if (Math.abs(
                p - Math.rint(p)
        ) < 0.001) {

            return String.format(
                    Locale.US,
                    "%.0f",
                    p
            );
        }

        return String.format(
                Locale.US,
                "%.2f",
                p
        );
    }

    private void toast(String s) {

        Toast.makeText(
                this,
                s,
                Toast.LENGTH_LONG
        ).show();
    }

    @Override
    protected void onDestroy() {

        if (parser != null) {
            // WebView instances are released by parser.
        }

        pool.shutdownNow();

        db.close();

        super.onDestroy();
    }
}
