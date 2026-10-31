package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The Home tab (our build), after Shows: the house at a glance in the style of the Glance dashboard's TV view. At the
 * left, a banner (all secure, or what needs a look) and six status tiles; at the right, the clock and the weather, and
 * the light rows, which OK switches. HomeView draws it on the hero layer in place of the cards, always with the night
 * look, on the Glance background (sky()); HomeStatus reads Home Assistant while the tab shows.
 * Measurements are in the 1920×1080 space; everything stays above 790 (the hero layer's height).
 */
final class HomeTab {
    private static final float LEFT = 80, MID = 1120, RIGHT_X = 1160, RIGHT = 1840, TOP = 176, BOTTOM = 780;
    private static final float BANNER_H = 120, GAP = 20, WEATHER_H = 230, CARD_R = 22, TILE_R = 20;
    private static final float ROW_H = 62, ROW_GAP = 6, LIGHTS_FIRST = 80;
    private static final int TILES = 6, COLUMNS = 3;
    private static final int CARD = 0x14FFFFFF, CARD_LINE = 0x10FFFFFF, CARD_FOCUS = 0x2EFFFFFF, WHITE = 0xFFF5F5F7,
            DIM = 0xA6FFFFFF, FAINT = 0x80FFFFFF;
    /** Tile and light symbols by position (the tiles' own come with their status). */
    private static final String[] LIGHT_SYMBOLS = {"ceiling_light", "floor_lamp", "tv", "countertops", "lightbulb"};

    static boolean offered() {
        return Config.HOME_TAB && HomeAssistant.offered();
    }

    private final Context context;
    private final Runnable changed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HomeStatus.Snapshot snap = HomeStatus.snapshot(System.currentTimeMillis());
    private boolean shown;
    private int index;
    private final int count = TILES + HomeStatus.LIGHTS.size();
    private final Spring[] focus = new Spring[TILES + HomeStatus.MAX_LIGHTS];
    private final RectF banner = new RectF(), weather = new RectF(), lights = new RectF(), cell = new RectF();
    private final RectF[] tiles = new RectF[TILES], rows = new RectF[HomeStatus.MAX_LIGHTS];
    private float k = 1;
    private TextPaint bannerTitle, bannerSub, tileTitle, tileSub, rowName, header, headerCount, date, clock, temp, cond, updated;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader skyBase, skyBlue, skyPurple;
    private int skyH;
    // Texts cut to their boxes once per snapshot, never while drawing.
    private String bannerTitleText = "", bannerSubText = "", headerText = "", countText = "";
    private final String[] tileTitles = new String[TILES], tileSubs = new String[TILES];
    private final String[] rowNames = new String[HomeStatus.MAX_LIGHTS];
    private String tempText = "", condText = "", weatherSymbol = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            HomeStatus.refresh(context, HomeTab.this::update);
            handler.postDelayed(this, HomeStatus.REFRESH_MS);
        }
    };

    HomeTab(Context context, Runnable changed) {
        this.context = context;
        this.changed = changed;
        for (int i = 0; i < focus.length; i++) focus[i] = new Spring(0.3f, 0.82f, 0);
        for (int i = 0; i < tiles.length; i++) tiles[i] = new RectF();
        for (int i = 0; i < rows.length; i++) rows[i] = new RectF();
    }

    // MARK: - Showing

    /** The tab is on screen (and the home screen running): read now, then every 30 s. */
    void show(boolean on) {
        if (on == shown) return;
        shown = on;
        handler.removeCallbacks(tick);
        if (on) tick.run();
    }

    /** New states, or a new minute: the texts again. */
    void update() {
        snap = HomeStatus.snapshot(System.currentTimeMillis());
        cutTexts();
        changed.run();
    }

    // MARK: - Layout

    void layout(float k) {
        this.k = k;
        banner.set(LEFT * k, TOP * k, MID * k, (TOP + BANNER_H) * k);
        float tw = (MID - LEFT - GAP * (COLUMNS - 1)) / COLUMNS, top = TOP + BANNER_H + GAP;
        float th = (BOTTOM - top - GAP) / 2;
        for (int i = 0; i < TILES; i++) {
            float x = LEFT + (i % COLUMNS) * (tw + GAP), y = top + (i / COLUMNS) * (th + GAP);
            tiles[i].set(x * k, y * k, (x + tw) * k, (y + th) * k);
        }
        weather.set(RIGHT_X * k, TOP * k, RIGHT * k, (TOP + WEATHER_H) * k);
        lights.set(RIGHT_X * k, (TOP + WEATHER_H + GAP) * k, RIGHT * k, BOTTOM * k);
        for (int i = 0; i < rows.length; i++) {
            float y = lights.top / k + LIGHTS_FIRST + i * (ROW_H + ROW_GAP);
            rows[i].set((RIGHT_X + 20) * k, y * k, (RIGHT - 20) * k, (y + ROW_H) * k);
        }
        bannerTitle = paint(Ui.semibold, 40, WHITE);
        bannerSub = paint(Ui.medium, 25, DIM);
        tileTitle = paint(Ui.semibold, 30, WHITE);
        tileSub = paint(Ui.medium, 23, DIM);
        rowName = paint(Ui.medium, 27, WHITE);
        header = paint(Ui.semibold, 20, DIM);
        header.setLetterSpacing(0.08f);
        headerCount = paint(Ui.semibold, 20, HomeStatus.AMBER);
        date = paint(Ui.medium, 25, DIM);
        clock = new TextPaint(Ui.numbers(Ui.thin, 116 * k, WHITE));
        clock.setLetterSpacing(-0.02f);
        temp = new TextPaint(Ui.numbers(Ui.light, 76 * k, WHITE));
        temp.setTextAlign(Paint.Align.RIGHT);
        cond = paint(Ui.medium, 23, DIM);
        cond.setTextAlign(Paint.Align.RIGHT);
        updated = paint(Ui.medium, 22, FAINT);
        updated.setTextAlign(Paint.Align.RIGHT);
        line.setStyle(Paint.Style.STROKE);
        String[] symbols = {"shield_ok", "shield_alert", "videocam", "thermostat", "laundry", "printer", "smoke", "pets"};
        for (String s : symbols) Symbols.warm(context, s, 34 * k);
        for (String s : LIGHT_SYMBOLS) Symbols.warm(context, s, 28 * k);
        Symbols.warm(context, "shield_ok", 40 * k);
        Symbols.warm(context, "shield_alert", 40 * k);
        Symbols.warmWeather(context, 40 * k);
        skyBase = null;
        cutTexts();
    }

    private TextPaint paint(android.graphics.Typeface face, float size, int color) {
        return new TextPaint(Ui.text(face, size * k, color));
    }

    private void cutTexts() {
        if (bannerTitle == null) return;
        float pad = 36 * k, iconW = (76 + 28) * k;
        bannerTitleText = cut(snap.banner.title, bannerTitle, banner.width() - 2 * pad - iconW);
        bannerSubText = cut(snap.banner.sub, bannerSub, banner.width() - 2 * pad - iconW);
        for (int i = 0; i < TILES; i++) {
            float w = tiles[i].width() - 44 * k;
            tileTitles[i] = cut(snap.tiles[i].title, tileTitle, w);
            tileSubs[i] = cut(snap.tiles[i].sub, tileSub, w);
        }
        for (int i = 0; i < snap.lights.size(); i++) {
            rowNames[i] = cut(snap.lights.get(i).group.name, rowName, rows[i].width() - (64 + 84 + 40) * k);
        }
        headerText = "LIGHTS";
        countText = snap.lightsOn > 0 ? " · " + HomeStatus.fmt("{n} on", "n", snap.lightsOn) : "";
    }

    private static String cut(String s, TextPaint p, float width) {
        return TextUtils.ellipsize(s, p, Math.max(0, width), TextUtils.TruncateAt.END).toString();
    }

    /** The weather from the home screen's own forecast (Open-Meteo), once per reading. */
    void weather(HomeData.Weather wx) {
        if (wx == null || Double.isNaN(wx.temp)) {
            tempText = condText = weatherSymbol = "";
            return;
        }
        tempText = HomeData.degrees(wx.temp);
        condText = HomeData.condition(wx.code, wx.isDay) + " · " + HomeData.highLow(wx, " ");
        weatherSymbol = Symbols.weather(wx.code, wx.isDay);
    }

    // MARK: - Drawing (no allocations: the texts are cut when they change)

    /** The Glance TV background: a deep blue gradient lit blue at the top left and purple at the bottom right. */
    void drawSky(Canvas c, RectF screen, float alpha) {
        int h = Math.round(screen.height()), w = Math.round(screen.width());
        if (skyBase == null || skyH != h) {
            skyH = h;
            skyBase = new LinearGradient(0, 0, 0, h, new int[] {0xFF1B2236, 0xFF121726, 0xFF0C0F1A},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            skyBlue = new RadialGradient(0.1f * w, 0, 0.9f * w, 0x8C586EBE, 0x00586EBE, Shader.TileMode.CLAMP);
            skyPurple = new RadialGradient(0.95f * w, h, 0.8f * w, 0x73784AAA, 0x00784AAA, Shader.TileMode.CLAMP);
        }
        int a = Math.round(255 * alpha);
        skyLayer(c, screen, skyBase, a);
        skyLayer(c, screen, skyBlue, a);
        skyLayer(c, screen, skyPurple, a);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    private void skyLayer(Canvas c, RectF screen, Shader s, int alpha) {
        fill.setShader(s);
        fill.setAlpha(alpha);
        c.drawRect(screen, fill);
    }

    /** `buttonsLeft`: where the round buttons start, for the "Updated" line beside them. */
    void draw(Canvas c, String dateText, String clockText, float buttonsLeft, float buttonsCy) {
        if (bannerTitle == null) return;
        if (!snap.updated.isEmpty()) {
            updated.setColor(snap.stale ? HomeStatus.AMBER : FAINT);
            c.drawText(snap.updated, buttonsLeft - 28 * k, buttonsCy + Ui.halfCap(updated), updated);
        }
        drawBanner(c);
        for (int i = 0; i < TILES; i++) drawTile(c, i);
        drawWeather(c, dateText, clockText);
        drawLights(c);
    }

    private void card(Canvas c, RectF r, float radius, int color) {
        fill.setColor(color);
        c.drawRoundRect(r, radius, radius, fill);
        line.setColor(CARD_LINE);
        line.setStrokeWidth(Math.max(1, k));
        c.drawRoundRect(r, radius, radius, line);
    }

    private void drawBanner(Canvas c) {
        HomeStatus.Line l = snap.banner;
        card(c, banner, 24 * k, CARD);
        float r = 38 * k, cx = banner.left + 36 * k + r, cy = banner.centerY();
        fill.setColor(Ui.alpha(l.color, 0.18f));
        c.drawCircle(cx, cy, r, fill);
        Symbols.draw(context, c, l.symbol, cx, cy, 40 * k, l.color);
        float x = cx + r + 28 * k;
        if (bannerSubText.isEmpty()) {
            c.drawText(bannerTitleText, x, cy + Ui.halfCap(bannerTitle), bannerTitle);
        } else {
            c.drawText(bannerTitleText, x, cy - 4 * k, bannerTitle);
            c.drawText(bannerSubText, x, cy + 36 * k, bannerSub);
        }
    }

    private void drawTile(Canvas c, int i) {
        HomeStatus.Line l = snap.tiles[i];
        RectF r = tiles[i];
        float f = Ui.clamp(focus[i].value), s = 1 + 0.05f * focus[i].value;
        c.save();
        c.scale(s, s, r.centerX(), r.centerY());
        card(c, r, TILE_R * k, blend(CARD, CARD_FOCUS, f));
        if (f > 0.01f) ring(c, r, TILE_R * k, f);
        fill.setColor(l.color);
        c.drawCircle(r.right - 31 * k, r.top + 33 * k, 7 * k, fill);
        Symbols.draw(context, c, l.symbol, r.left + 42 * k, r.top + 46 * k, 34 * k, l.color == HomeStatus.GRAY ? 0xFFCFD6E6 : l.color);
        c.drawText(tileTitles[i], r.left + 22 * k, r.bottom - 52 * k, tileTitle);
        c.drawText(tileSubs[i], r.left + 22 * k, r.bottom - 20 * k, tileSub);
        c.restore();
    }

    private void ring(Canvas c, RectF r, float radius, float f) {
        line.setColor(Ui.alpha(0xFFFFFFFF, f));
        line.setStrokeWidth(4 * k);
        cell.set(r);
        cell.inset(-2 * k, -2 * k);
        c.drawRoundRect(cell, radius + 2 * k, radius + 2 * k, line);
    }

    private void drawWeather(Canvas c, String dateText, String clockText) {
        card(c, weather, CARD_R * k, CARD);
        float pad = 36 * k;
        c.drawText(dateText, weather.left + pad, weather.top + 62 * k, date);
        c.drawText(clockText, weather.left + pad - 4 * k, weather.bottom - 38 * k, clock);
        if (tempText.isEmpty()) return;
        c.drawText(tempText, weather.right - pad, weather.bottom - 70 * k, temp);
        c.drawText(condText, weather.right - pad, weather.bottom - 34 * k, cond);
        Symbols.draw(context, c, weatherSymbol, weather.right - pad - 24 * k, weather.top + 58 * k, 40 * k, 0xFFCFD6E6);
    }

    private void drawLights(Canvas c) {
        card(c, lights, CARD_R * k, CARD);
        float x = lights.left + 36 * k, y = lights.top + 52 * k;
        c.drawText(headerText, x, y, header);
        if (!countText.isEmpty()) c.drawText(countText, x + header.measureText(headerText), y, headerCount);
        for (int i = 0; i < snap.lights.size(); i++) {
            HomeStatus.Light l = snap.lights.get(i);
            RectF r = rows[i];
            float f = Ui.clamp(focus[TILES + i].value), rr = 14 * k;
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f * f));
                c.drawRoundRect(r, rr, rr, fill);
                ring(c, r, rr, f);
            }
            float a = l.unavailable ? 0.4f : 1, cy = r.centerY();
            int on = Ui.alpha(HomeStatus.AMBER, a), off = Ui.alpha(HomeStatus.GRAY, a);
            Symbols.draw(context, c, LIGHT_SYMBOLS[Math.min(i, LIGHT_SYMBOLS.length - 1)], r.left + 34 * k, cy, 28 * k, l.on ? on : off);
            rowName.setAlpha(Math.round(255 * a));
            c.drawText(rowNames[i], r.left + 72 * k, cy + Ui.halfCap(rowName), rowName);
            // The switch.
            float sw = 64 * k, sh = 36 * k, sx = r.right - 16 * k - sw;
            cell.set(sx, cy - sh / 2, sx + sw, cy + sh / 2);
            fill.setColor(l.on ? on : Ui.alpha(0xFFFFFFFF, 0.16f * a));
            c.drawRoundRect(cell, sh / 2, sh / 2, fill);
            fill.setColor(Ui.alpha(l.on ? 0xFFFFFFFF : 0xFFCFD6E6, a));
            c.drawCircle(l.on ? cell.right - sh / 2 : cell.left + sh / 2, cy, 14 * k, fill);
        }
        rowName.setAlpha(255);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    // MARK: - Focus: six tiles (3 × 2), then the light rows at the right

    boolean step(float dt) {
        boolean moving = false;
        for (Spring s : focus) moving |= s.step(dt);
        return moving;
    }

    /** Only the focused item at 1 (none when `on` is false); true if anything will move. */
    boolean aim(boolean on, boolean snapNow) {
        boolean changedAny = false;
        for (int i = 0; i < focus.length; i++) {
            float t = on && i == index ? 1 : 0;
            changedAny |= focus[i].target != t;
            focus[i].target = t;
            if (snapNow) focus[i].snap(t);
        }
        return changedAny;
    }

    /** Focus comes in from the tabs (`fromTop`) or from the shelf, near `x`. */
    void enter(boolean fromTop, float x) {
        int lightsN = count - TILES;
        if (x > RIGHT_X * k && lightsN > 0) {
            index = TILES + (fromTop ? 0 : lightsN - 1);
            return;
        }
        int row = fromTop ? 0 : 1, best = 0;
        float d = Float.MAX_VALUE;
        for (int col = 0; col < COLUMNS; col++) {
            float dd = Math.abs(tiles[row * COLUMNS + col].centerX() - x);
            if (dd < d) {
                d = dd;
                best = col;
            }
        }
        index = row * COLUMNS + best;
    }

    /** -1: leave upward (to the tabs), 1: downward (to the shelf), 0: moved inside (or stayed at an edge). */
    int move(int dx, int dy) {
        int lightsN = count - TILES;
        if (index < TILES) {
            int row = index / COLUMNS, col = index % COLUMNS;
            if (dy < 0) {
                if (row == 0) return -1;
                index -= COLUMNS;
            } else if (dy > 0) {
                if (row == 1) return 1;
                index += COLUMNS;
            } else if (dx > 0 && col == COLUMNS - 1) {
                if (lightsN > 0) index = TILES + (row == 0 ? 0 : Math.min(lightsN - 1, lightsN / 2));
            } else if (dx < 0 && col == 0) {
                return 0;
            } else {
                index += dx;
            }
        } else {
            int i = index - TILES;
            if (dy < 0) {
                if (i == 0) return -1;
                index--;
            } else if (dy > 0) {
                if (i == lightsN - 1) return 1;
                index++;
            } else if (dx < 0) {
                index = (i < lightsN / 2 ? 0 : COLUMNS) + COLUMNS - 1;
            }
        }
        return 0;
    }

    /** OK: a light row switches; the tiles are only read. */
    void ok(Click click) {
        if (index < TILES) return;
        HomeStatus.Light l = index - TILES < snap.lights.size() ? snap.lights.get(index - TILES) : null;
        if (l == null || l.unavailable) return;
        click.open();
        HomeStatus.toggle(context, l, this::update);
    }

    /** The focused item's center x, for moving on to the tabs or the shelf. */
    float focusedX() {
        return index < TILES ? tiles[index].centerX() : rows[Math.min(index - TILES, rows.length - 1)].centerX();
    }
}
