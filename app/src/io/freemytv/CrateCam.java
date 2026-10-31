package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The crate tile's picture (our build): a still from the crate camera through Home Assistant's camera proxy, every
 * REFRESH_MS while the Home tab shows, decoded off the main thread at about the tile's size. Live video is tinyCam's:
 * OK on the tile opens it there (home_tab_crate_link).
 */
final class CrateCam {
    static final long REFRESH_MS = 2500, STALE_MS = 15_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "crate-cam");
        t.setDaemon(true);
        return t;
    });

    private final Context app;
    private final String entity = HomeStatus.crateCamera();
    private final Runnable changed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean fetching;
    private boolean running;
    private int width, height;
    /** The last picture and when it came (main thread). */
    Bitmap frame;
    long frameAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            fetch();
            if (frame != null && System.currentTimeMillis() - frameAt > STALE_MS) changed.run();   // shows "Offline"
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    CrateCam(Context c, Runnable changed) {
        app = c.getApplicationContext();
        this.changed = changed;
    }

    boolean available() {
        return !entity.isEmpty();
    }

    /** About the size it's drawn at (the focused tile's), so a 1080p still isn't decoded in full. */
    void size(int w, int h) {
        width = w;
        height = h;
    }

    void run(boolean on) {
        if (on == running || !available()) return;
        running = on;
        handler.removeCallbacks(tick);
        if (on) tick.run();
    }

    private void fetch() {
        if (fetching) return;
        fetching = true;
        final int w = width, h = height;
        IO.execute(() -> {
            Bitmap b = null;
            try {
                b = decode(get(HomeAssistant.token(app), "/api/camera_proxy/" + entity), w, h);
            } catch (IOException | RuntimeException e) {   // nothing may escape :home's threads
                AppLog.w("crate cam: " + e.getMessage());
            } finally {
                fetching = false;
            }
            if (b == null) return;
            final Bitmap picture = b;
            handler.post(() -> {
                frame = picture;
                frameAt = System.currentTimeMillis();
                changed.run();
            });
        });
    }

    private static Bitmap decode(byte[] jpeg, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int sample = 1;
        while (w > 0 && h > 0 && o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    /** The JPEG, from your own server only (redirects aren't followed: the token would go along). */
    private static byte[] get(String token, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(HomeAssistant.SERVER + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
