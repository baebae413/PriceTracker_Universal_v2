package ru.pricetracker;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class PriceDb extends SQLiteOpenHelper {

    private static final String DB = "prices.db";
    private static final int DB_VERSION = 2;

    public PriceDb(Context c) {
        super(c, DB, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE products (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT UNIQUE NOT NULL," +
                "name TEXT," +
                "price REAL," +
                "old_price REAL," +
                "last_price REAL," +
                "checked_at INTEGER DEFAULT 0," +
                "status TEXT DEFAULT 'new')");

        createHistoryTable(db);
    }

    private void createHistoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS price_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "product_id INTEGER NOT NULL," +
                "price REAL NOT NULL," +
                "checked_at INTEGER NOT NULL," +
                "FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createHistoryTable(db);

            // Добавляем текущую цену каждого уже существующего товара
            // в новую историю.
            Cursor c = db.query(
                    "products",
                    new String[]{"id", "last_price", "checked_at"},
                    null,
                    null,
                    null,
                    null,
                    null
            );

            while (c.moveToNext()) {
                long productId = c.getLong(0);
                double price = c.getDouble(1);
                long checkedAt = c.getLong(2);

                if (checkedAt <= 0) {
                    checkedAt = System.currentTimeMillis();
                }

                ContentValues v = new ContentValues();
                v.put("product_id", productId);
                v.put("price", price);
                v.put("checked_at", checkedAt);

                db.insert("price_history", null, v);
            }

            c.close();
        }
    }

    public long add(String url, String name, double price, double oldPrice) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("name", name);
        v.put("price", price);
        v.put("old_price", oldPrice);
        v.put("last_price", price);
        v.put("checked_at", System.currentTimeMillis());
        v.put("status", "new");

        long id = db.insertWithOnConflict(
                "products",
                null,
                v,
                SQLiteDatabase.CONFLICT_IGNORE
        );

        // Если товар действительно новый — записываем первую цену.
        if (id != -1) {
            addHistory(db, id, price, System.currentTimeMillis());
        }

        return id;
    }

    private void addHistory(SQLiteDatabase db, long productId,
                            double price, long checkedAt) {

        // Не добавляем одинаковую цену подряд.
        Cursor c = db.query(
                "price_history",
                new String[]{"price"},
                "product_id=?",
                new String[]{String.valueOf(productId)},
                null,
                null,
                "checked_at DESC",
                "1"
        );

        boolean same = false;

        if (c.moveToFirst()) {
            double last = c.getDouble(0);
            same = Math.abs(last - price) < 0.001;
        }

        c.close();

        if (same) {
            return;
        }

        ContentValues v = new ContentValues();
        v.put("product_id", productId);
        v.put("price", price);
        v.put("checked_at", checkedAt);

        db.insert("price_history", null, v);
    }

    public List<Product> all() {
        ArrayList<Product> out = new ArrayList<>();

        Cursor c = getReadableDatabase().query(
                "products",
                null,
                null,
                null,
                null,
                null,
                "id DESC"
        );

        while (c.moveToNext()) {
            Product p = new Product();

            p.id = c.getLong(c.getColumnIndexOrThrow("id"));
            p.url = c.getString(c.getColumnIndexOrThrow("url"));
            p.name = c.getString(c.getColumnIndexOrThrow("name"));
            p.price = c.getDouble(c.getColumnIndexOrThrow("price"));
            p.oldPrice = c.getDouble(c.getColumnIndexOrThrow("old_price"));
            p.lastPrice = c.getDouble(c.getColumnIndexOrThrow("last_price"));
            p.checkedAt = c.getLong(c.getColumnIndexOrThrow("checked_at"));
            p.status = c.getString(c.getColumnIndexOrThrow("status"));

            out.add(p);
        }

        c.close();
        return out;
    }

    public void updatePrice(long id, double newPrice, String status) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues v = new ContentValues();
        v.put("last_price", newPrice);
        v.put("price", newPrice);
        v.put("checked_at", System.currentTimeMillis());
        v.put("status", status);

        db.update(
                "products",
                v,
                "id=?",
                new String[]{String.valueOf(id)}
        );

        addHistory(
                db,
                id,
                newPrice,
                System.currentTimeMillis()
        );
    }

    public List<History> history(long productId) {
        ArrayList<History> out = new ArrayList<>();

        Cursor c = getReadableDatabase().query(
                "price_history",
                null,
                "product_id=?",
                new String[]{String.valueOf(productId)},
                null,
                null,
                "checked_at DESC"
        );

        while (c.moveToNext()) {
            History h = new History();

            h.id = c.getLong(
                    c.getColumnIndexOrThrow("id")
            );

            h.productId = c.getLong(
                    c.getColumnIndexOrThrow("product_id")
            );

            h.price = c.getDouble(
                    c.getColumnIndexOrThrow("price")
            );

            h.checkedAt = c.getLong(
                    c.getColumnIndexOrThrow("checked_at")
            );

            out.add(h);
        }

        c.close();
        return out;
    }

    public void deleteHistory(long historyId) {
        getWritableDatabase().delete(
                "price_history",
                "id=?",
                new String[]{String.valueOf(historyId)}
        );
    }

    public void clearHistory(long productId) {
        getWritableDatabase().delete(
                "price_history",
                "product_id=?",
                new String[]{String.valueOf(productId)}
        );
    }

    public double minPrice(long productId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MIN(price) FROM price_history WHERE product_id=?",
                new String[]{String.valueOf(productId)}
        );

        double result = 0;

        if (c.moveToFirst() && !c.isNull(0)) {
            result = c.getDouble(0);
        }

        c.close();
        return result;
    }

    public double maxPrice(long productId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(price) FROM price_history WHERE product_id=?",
                new String[]{String.valueOf(productId)}
        );

        double result = 0;

        if (c.moveToFirst() && !c.isNull(0)) {
            result = c.getDouble(0);
        }

        c.close();
        return result;
    }

    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();

        db.delete(
                "price_history",
                "product_id=?",
                new String[]{String.valueOf(id)}
        );

        db.delete(
                "products",
                "id=?",
                new String[]{String.valueOf(id)}
        );
    }

    public static class Product {
        long id;
        String url, name, status;
        double price, oldPrice, lastPrice;
        long checkedAt;
    }

    public static class History {
        long id;
        long productId;
        double price;
        long checkedAt;
    }
}
