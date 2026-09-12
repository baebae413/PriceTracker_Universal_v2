package ru.pricetracker;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PriceCheckWorker extends Worker {
    private final Context context;

    public PriceCheckWorker(Context context, WorkerParameters params) {
        super(context, params);
        this.context = context.getApplicationContext();
    }

    @Override public Result doWork() {
        PriceDb db = new PriceDb(context);
        List<PriceDb.Product> products = db.enabledProducts();
        if (products.isEmpty()) return Result.success();

        for (PriceDb.Product product : products) {
            if (isStopped()) return Result.retry();
            CountDownLatch latch = new CountDownLatch(1);
            UniversalParser parser = new UniversalParser(context);
            parser.product(product.url, new UniversalParser.Callback() {
                @Override public void success(UniversalParser.Result r) {
                    try {
                        if (r.price >= 1 && r.price <= 100000000) {
                            double old = product.lastPrice;
                            if (r.price < old - 0.001) {
                                db.updatePrice(product.id, r.price, "down");
                                notifyDrop(product, old, r.price);
                            } else if (r.price > old + 0.001) {
                                db.updatePrice(product.id, r.price, "up");
                            } else {
                                db.updatePrice(product.id, r.price, "same");
                            }
                        }
                    } finally { latch.countDown(); }
                }
                @Override public void error(Exception e) { latch.countDown(); }
            });
            try { latch.await(35, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return Result.retry();
            }
        }
        return Result.success();
    }

    private void notifyDrop(PriceDb.Product product, double oldPrice, double newPrice) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, (int) product.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = format(oldPrice) + " ₽ → " + format(newPrice) + " ₽";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🔔 Цена снизилась")
                .setContentText(product.name + ": " + text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(product.name + "\n" + text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pending);
        try { NotificationManagerCompat.from(context).notify((int) product.id, builder.build()); }
        catch (SecurityException ignored) { }
    }

    private String format(double p) {
        return Math.abs(p - Math.rint(p)) < 0.001 ? String.format(java.util.Locale.US, "%.0f", p)
                : String.format(java.util.Locale.US, "%.2f", p);
    }
}
