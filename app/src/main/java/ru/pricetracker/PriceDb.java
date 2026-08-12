package ru.pricetracker;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class PriceDb extends SQLiteOpenHelper {
    private static final String DB = "prices.db";
    public PriceDb(Context c) { super(c, DB, null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE products (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT UNIQUE NOT NULL," +
                "name TEXT," +
                "price REAL," +
                "old_price REAL," +
                "last_price REAL," +
                "checked_at INTEGER DEFAULT 0," +
                "status TEXT DEFAULT 'new')");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public long add(String url, String name, double price, double oldPrice) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("url", url); v.put("name", name); v.put("price", price);
        v.put("old_price", oldPrice); v.put("last_price", price);
        v.put("checked_at", System.currentTimeMillis());
        v.put("status", "new");
        return db.insertWithOnConflict("products", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<Product> all() {
        ArrayList<Product> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("products",
                null, null, null, null, null, "id DESC");
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
        ContentValues v = new ContentValues();
        v.put("last_price", newPrice);
        v.put("price", newPrice);
        v.put("checked_at", System.currentTimeMillis());
        v.put("status", status);
        getWritableDatabase().update("products", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("products", "id=?", new String[]{String.valueOf(id)});
    }

    public static class Product {
        long id;
        String url, name, status;
        double price, oldPrice, lastPrice;
        long checkedAt;
    }
}