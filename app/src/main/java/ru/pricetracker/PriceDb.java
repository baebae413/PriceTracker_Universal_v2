package ru.pricetracker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class PriceDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "prices.db";
    private static final int DB_VERSION = 3;

    public PriceDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE products (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT UNIQUE NOT NULL," +
                "name TEXT NOT NULL," +
                "site TEXT NOT NULL," +
                "last_price REAL NOT NULL," +
                "checked_at INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'new'," +
                "enabled INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE price_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "product_id INTEGER NOT NULL," +
                "price REAL NOT NULL," +
                "checked_at INTEGER NOT NULL," +
                "FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE products ADD COLUMN site TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE products ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1");
        }
    }

    public synchronized long add(String url, String name, String site, double price) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("name", name == null || name.trim().isEmpty() ? site : name.trim());
        v.put("site", site == null ? "" : site);
        v.put("last_price", price);
        v.put("checked_at", System.currentTimeMillis());
        v.put("status", "new");
        v.put("enabled", 1);
        long id = db.insertWithOnConflict("products", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id != -1) addHistoryIfChanged(db, id, price, System.currentTimeMillis());
        return id;
    }

    public synchronized void updatePrice(long id, double price, String status) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("last_price", price);
        v.put("checked_at", System.currentTimeMillis());
        v.put("status", status);
        db.update("products", v, "id=?", new String[]{String.valueOf(id)});
        addHistoryIfChanged(db, id, price, System.currentTimeMillis());
    }

    private void addHistoryIfChanged(SQLiteDatabase db, long productId, double price, long time) {
        Cursor c = db.rawQuery("SELECT price FROM price_history WHERE product_id=? ORDER BY checked_at DESC LIMIT 1",
                new String[]{String.valueOf(productId)});
        boolean same = c.moveToFirst() && Math.abs(c.getDouble(0) - price) < 0.001;
        c.close();
        if (same) return;
        ContentValues v = new ContentValues();
        v.put("product_id", productId);
        v.put("price", price);
        v.put("checked_at", time);
        db.insert("price_history", null, v);
    }

    public synchronized List<Product> all() {
        return queryProducts(false);
    }

    public synchronized List<Product> enabledProducts() {
        return queryProducts(true);
    }

    private List<Product> queryProducts(boolean enabledOnly) {
        ArrayList<Product> out = new ArrayList<>();
        String selection = enabledOnly ? "enabled=1" : null;
        Cursor c = getReadableDatabase().query("products", null, selection, null, null, null, "id DESC");
        while (c.moveToNext()) {
            Product p = new Product();
            p.id = c.getLong(c.getColumnIndexOrThrow("id"));
            p.url = c.getString(c.getColumnIndexOrThrow("url"));
            p.name = c.getString(c.getColumnIndexOrThrow("name"));
            p.site = c.getString(c.getColumnIndexOrThrow("site"));
            p.lastPrice = c.getDouble(c.getColumnIndexOrThrow("last_price"));
            p.checkedAt = c.getLong(c.getColumnIndexOrThrow("checked_at"));
            p.status = c.getString(c.getColumnIndexOrThrow("status"));
            p.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
            out.add(p);
        }
        c.close();
        return out;
    }

    public synchronized List<History> history(long productId) {
        ArrayList<History> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("price_history", null, "product_id=?",
                new String[]{String.valueOf(productId)}, null, null, "checked_at DESC");
        while (c.moveToNext()) {
            History h = new History();
            h.id = c.getLong(c.getColumnIndexOrThrow("id"));
            h.productId = c.getLong(c.getColumnIndexOrThrow("product_id"));
            h.price = c.getDouble(c.getColumnIndexOrThrow("price"));
            h.checkedAt = c.getLong(c.getColumnIndexOrThrow("checked_at"));
            out.add(h);
        }
        c.close();
        return out;
    }

    public synchronized void deleteHistory(long id) {
        getWritableDatabase().delete("price_history", "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void clearHistory(long productId) {
        getWritableDatabase().delete("price_history", "product_id=?", new String[]{String.valueOf(productId)});
    }

    public synchronized void delete(long id) {
        getWritableDatabase().delete("products", "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void setEnabled(long id, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        getWritableDatabase().update("products", v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized double minPrice(long productId) {
        return aggregate(productId, "MIN");
    }

    public synchronized double maxPrice(long productId) {
        return aggregate(productId, "MAX");
    }

    private double aggregate(long productId, String fn) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + fn + "(price) FROM price_history WHERE product_id=?",
                new String[]{String.valueOf(productId)});
        double result = 0;
        if (c.moveToFirst() && !c.isNull(0)) result = c.getDouble(0);
        c.close();
        return result;
    }

    public static class Product {
        long id;
        String url, name, site, status;
        double lastPrice;
        long checkedAt;
        boolean enabled;
    }

    public static class History {
        long id, productId, checkedAt;
        double price;
    }
}
