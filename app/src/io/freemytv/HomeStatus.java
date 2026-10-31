package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the Home tab shows (our build): the house's status and a few lights, read from your Home Assistant with the
 * token tools/send_key.py sent (HomeAssistant's). The entities come from config.properties (home_tab_*), as named
 * groups: "Name=entity+entity, Name=entity". Only the light rows switch anything; the lock, the garage and the alarms
 * are only read. The states are read while the tab shows (when it opens, then every 30 s), never while it doesn't.
 * Its text is English only, outside the catalogues (app/assets/i18n), so upstream's translations merge without conflicts.
 */
final class HomeStatus {
    private HomeStatus() {}

    static final long REFRESH_MS = 30_000;
    /** Light rows: the panel fits this many. */
    static final int MAX_LIGHTS = 5;

    static final int GREEN = 0xFF4CC38A, AMBER = 0xFFF2B134, RED = 0xFFEF5B5B, BLUE = 0xFF5AA9FF, PURPLE = 0xFFA78BFA,
            GRAY = 0xFF8A93A6;

    /** A named group of entities from a home_tab_* setting. */
    static final class Group {
        final String name;
        final String[] entities;

        Group(String name, String[] entities) {
            this.name = name;
            this.entities = entities;
        }
    }

    /** One entity as last read. `absent`: the server doesn't know it (404), so it's left out rather than shown as offline. */
    private static final class State {
        final String state;
        final JSONObject attributes;
        final long changed;
        final boolean absent;

        State(String state, JSONObject attributes, long changed, boolean absent) {
            this.state = state;
            this.attributes = attributes;
            this.changed = changed;
            this.absent = absent;
        }
    }

    /** A status line: banner or tile. */
    static final class Line {
        String symbol = "", title = "", sub = "";
        int color = GRAY;
    }

    /** A light row: on while any of its lights is. */
    static final class Light {
        final Group group;
        boolean on, unavailable;

        Light(Group group) {
            this.group = group;
        }
    }

    /** Everything the tab draws, made on the main thread after a read and once a minute (the "12 min ago"s). */
    static final class Snapshot {
        final Line banner = new Line();
        /** Cameras, Inside, Washer; Printers, Smoke & CO, Crate Cam. */
        final Line[] tiles = new Line[6];
        final List<Light> lights = new ArrayList<>();
        int lightsOn;
        String updated = "";
        boolean stale;

        Snapshot() {
            for (int i = 0; i < tiles.length; i++) tiles[i] = new Line();
        }
    }

    static final List<Group> LIGHTS = lights(parse(Config.HOME_TAB_LIGHTS));
    private static final List<Group> LOCK = parse(Config.HOME_TAB_LOCK), GARAGE = parse(Config.HOME_TAB_GARAGE),
            SMOKE = parse(Config.HOME_TAB_SMOKE), CLIMATE = parse(Config.HOME_TAB_CLIMATE),
            CAMERAS = parse(Config.HOME_TAB_CAMERAS), WASHER = parse(Config.HOME_TAB_WASHER),
            PRINTERS = parse(Config.HOME_TAB_PRINTERS), CRATE = parse(Config.HOME_TAB_CRATE);

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-status");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean fetching, reachable, noToken;
    private static volatile long readAt;

    // MARK: - Reading

    /** Reads every entity once, off the main thread; `done` runs on the main thread. */
    static void refresh(Context c, Runnable done) {
        if (fetching) return;
        fetching = true;
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            try {
                String token = HomeAssistant.token(app);
                noToken = false;
                for (String entity : entities()) read(entity, token);
                readAt = System.currentTimeMillis();
                reachable = true;
            } catch (IOException e) {
                noToken = !HomeAssistant.hasToken(app);
                reachable = false;
                AppLog.w("home tab: " + e.getMessage());
            } catch (RuntimeException e) {   // nothing may escape :home's threads (its watchdog counts a crash)
                reachable = false;
                AppLog.w("home tab: " + e);
            } finally {
                fetching = false;
            }
            MAIN.post(done);
        });
    }

    private static void read(String entity, String token) throws IOException {
        try {
            JSONObject o = new JSONObject(HomeAssistant.request(token, "GET", "/api/states/" + entity, null));
            STATES.put(entity, new State(o.optString("state", ""), o.optJSONObject("attributes"),
                    time(o.optString("last_changed", "")), false));
        } catch (HomeAssistant.NotFound e) {
            STATES.put(entity, new State("", null, 0, true));
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer for " + entity);
        }
    }

    private static Set<String> entities() {
        Set<String> out = new LinkedHashSet<>();
        for (List<Group> list : java.util.Arrays.asList(LIGHTS, LOCK, GARAGE, SMOKE, CLIMATE, CAMERAS, WASHER, PRINTERS, CRATE)) {
            for (Group g : list) Collections.addAll(out, g.entities);
        }
        return out;
    }

    // MARK: - OK on a light row

    /**
     * Turns the row's lights off if any is on, else on (one call for all of them, so a pair never ends up half on). The
     * row shows its new state at once and is read again a moment later; `done` runs on the main thread each time.
     */
    static void toggle(Context c, Light light, Runnable done) {
        if (light.unavailable) return;
        final Context app = c.getApplicationContext();
        final boolean turnOn = !light.on;
        for (String e : light.group.entities) {
            State s = STATES.get(e);
            if (s != null && !s.absent) STATES.put(e, new State(turnOn ? "on" : "off", s.attributes, s.changed, false));
        }
        MAIN.post(done);
        IO.execute(() -> {
            try {
                String token = HomeAssistant.token(app);
                JSONArray ids = new JSONArray();
                for (String e : light.group.entities) ids.put(e);
                String body = new JSONObject().put("entity_id", ids).toString();
                HomeAssistant.request(token, "POST", "/api/services/light/" + (turnOn ? "turn_on" : "turn_off"), body);
                Thread.sleep(1200);   // Home Assistant answers before the lights have changed
                for (String e : light.group.entities) read(e, token);
            } catch (IOException | org.json.JSONException | InterruptedException | RuntimeException e) {
                AppLog.w("home tab: " + light.group.name + ": " + e);
                try {
                    String token = HomeAssistant.token(app);
                    for (String id : light.group.entities) read(id, token);
                } catch (IOException | RuntimeException ignored) {
                }
            }
            MAIN.post(done);
        });
    }

    // MARK: - What it says

    /** Made from the last read, at `now`. Main thread. */
    static Snapshot snapshot(long now) {
        Snapshot s = new Snapshot();
        banner(s.banner);
        cameras(s.tiles[0], now);
        climate(s.tiles[1]);
        washer(s.tiles[2], now);
        printers(s.tiles[3]);
        smokeTile(s.tiles[4]);
        crate(s.tiles[5], now);
        if (readAt == 0) {   // nothing read yet: no "0 alarms clear" or "Unavailable" that isn't true
            for (Line t : s.tiles) {
                t.sub = "";
                t.color = GRAY;
            }
            s.tiles[1].title = "Inside";
        }
        for (Group g : LIGHTS) {
            Light l = new Light(g);
            boolean any = false;
            l.unavailable = true;
            for (String e : g.entities) {
                State st = STATES.get(e);
                if (st == null || st.absent) continue;
                any = true;
                if (!offline(st.state)) l.unavailable = false;
                if (st.state.equals("on")) l.on = true;
            }
            if (!any) l.unavailable = readAt == 0;   // not read yet: shown dimmed; a light the server doesn't know: too
            if (l.on) s.lightsOn++;
            s.lights.add(l);
        }
        s.stale = !reachable;
        if (noToken) s.updated = "No Home Assistant token on this TV";
        else if (!reachable && readAt == 0) s.updated = "";
        else if (!reachable) s.updated = "Home Assistant isn't answering";
        else s.updated = fmt("Updated {ago}", "ago", ago(readAt, now));
        return s;
    }

    private static void banner(Line l) {
        Smoke smoke = smoke();
        String lock = groupState(LOCK), garage = groupState(GARAGE);
        String lockName = LOCK.isEmpty() ? "" : LOCK.get(0).name, garageName = GARAGE.isEmpty() ? "" : GARAGE.get(0).name;
        if (readAt == 0) {
            l.symbol = "shield_ok";
            l.color = GRAY;
            l.title = noToken ? "Waiting for the token" : "Reading your home…";
            l.sub = noToken ? "Send it from your computer: python3 tools/send_key.py <TV IP> home_assistant_token" : "";
            return;
        }
        List<String> bad = new ArrayList<>();
        if (smoke.alarm > 0) bad.add("Smoke/CO alarm");
        if (lock != null && !lock.equals("locked")) bad.add(lockName + " " + word(lock));
        if (garage != null && !garage.equals("closed")) bad.add(garageName + " " + word(garage));
        if (smoke.offline > 0) bad.add(count(smoke.offline, "alarm offline", "alarms offline"));
        if (bad.isEmpty()) {
            l.symbol = "shield_ok";
            l.color = GREEN;
            l.title = "All secure";
            List<String> ok = new ArrayList<>();
            if (lock != null) ok.add(lockName + " " + word(lock));
            if (garage != null) ok.add(garageName + " " + word(garage));
            if (smoke.rooms > 0) ok.add(count(smoke.rooms, "alarm clear", "alarms clear"));
            l.sub = join(ok);
        } else {
            l.symbol = "shield_alert";
            l.color = smoke.alarm > 0 ? RED : AMBER;
            l.title = "Needs attention";
            l.sub = join(bad);
        }
    }

    private static void cameras(Line l, long now) {
        l.symbol = "videocam";
        l.color = BLUE;
        l.title = "Cameras";
        long latest = 0;
        String place = "", what = "";
        for (Group g : CAMERAS) {
            for (String e : g.entities) {
                State st = STATES.get(e);
                if (st == null || st.absent || offline(st.state)) continue;
                long at = time(st.state);   // an event entity's state is when it last fired
                if (at <= latest) continue;
                latest = at;
                place = g.name;
                String type = st.attributes == null ? "" : st.attributes.optString("event_type", "");
                what = e.contains("chime") || type.contains("ring") ? "ring"
                        : type.isEmpty() ? "motion" : type.replace("camera_", "").replace('_', ' ');
            }
        }
        l.sub = latest == 0 ? "No recent activity"
                : fmt("{place} {what} · {ago}", "place", place, "what", what, "ago", ago(latest, now));
    }

    private static void climate(Line l) {
        l.symbol = "thermostat";
        l.color = GRAY;
        List<String> rest = new ArrayList<>();
        String first = null;
        for (Group g : CLIMATE) {
            State st = g.entities.length == 0 ? null : STATES.get(g.entities[0]);
            String t = "–";
            if (st != null && !st.absent && st.attributes != null) {
                double v = st.attributes.optDouble("current_temperature", Double.NaN);
                if (!Double.isNaN(v)) t = Math.round(v) + "°";
            }
            if (first == null) first = t;
            else rest.add(g.name + " " + t);
        }
        l.title = fmt("Inside {t}", "t", first == null ? "–" : first);
        l.sub = join(rest);
    }

    private static void washer(Line l, long now) {
        l.symbol = "laundry";
        l.title = "Washer";
        State st = first(WASHER);
        if (st != null && st.state.equals("on")) {
            l.color = BLUE;
            l.sub = fmt("Running · {n} min", "n", Math.max(0, (now - st.changed) / 60_000));
        } else {
            l.color = GRAY;
            l.sub = st == null || offline(st.state) ? "Unavailable" : "Off";
        }
    }

    /** home_tab_printers: printing count + finished count + next finish time. */
    private static void printers(Line l) {
        l.symbol = "printer";
        Group g = PRINTERS.isEmpty() ? null : PRINTERS.get(0);
        int printing = g == null ? 0 : number(g, 0), done = g == null ? 0 : number(g, 1);
        if (printing == 0 && done == 0) {
            l.color = GRAY;
            l.title = "Printers";
            l.sub = "Idle";
            return;
        }
        l.color = GREEN;
        // The count in the title, the rest below: "4 printing · 9 finished" doesn't fit a tile.
        l.title = printing > 0 ? fmt("{n} printing", "n", printing) : fmt("{d} finished", "d", done);
        State next = g.entities.length > 2 ? STATES.get(g.entities[2]) : null;
        long at = next == null || next.absent ? 0 : time(next.state);
        String when = printing > 0 && at > 0 ? fmt("Next {time}", "time", Ui.time(at)) : "";
        if (printing == 0) l.sub = "Nothing printing";
        else l.sub = done > 0 ? (when.isEmpty() ? "" : when + " · ") + done + " done" : when;
    }

    private static void smokeTile(Line l) {
        Smoke s = smoke();
        l.symbol = "smoke";
        l.title = "Smoke & CO";
        if (s.alarm > 0) {
            l.color = RED;
            l.sub = "ALARM";
        } else if (s.offline > 0) {
            l.color = AMBER;
            l.sub = fmt("{n} of {m} online", "n", s.rooms - s.offline, "m", s.rooms);
        } else {
            l.color = s.rooms > 0 ? GREEN : GRAY;
            l.sub = count(s.rooms, "alarm clear", "alarms clear");
        }
    }

    /** home_tab_crate: its camera (shown in the tile, CrateCam) and a motion sensor: when it last saw motion. */
    private static void crate(Line l, long now) {
        l.symbol = "pets";
        l.color = PURPLE;
        Group g = CRATE.isEmpty() ? null : CRATE.get(0);
        l.title = g == null ? "Crate Cam" : g.name;
        State motion = null;
        if (g != null) {
            for (String e : g.entities) if (e.startsWith("binary_sensor.")) motion = STATES.get(e);
        }
        if (motion == null || motion.absent || offline(motion.state)) l.sub = "";
        else if (motion.state.equals("on")) l.sub = "Motion now";
        else l.sub = motion.changed == 0 ? "No motion yet" : fmt("Motion {ago}", "ago", ago(motion.changed, now));
    }

    /** The crate tile's camera (the first camera.* in home_tab_crate), or "" without one. */
    static String crateCamera() {
        if (CRATE.isEmpty()) return "";
        for (String e : CRATE.get(0).entities) if (e.startsWith("camera.")) return e;
        return "";
    }

    private static final class Smoke {
        int rooms, alarm, offline;
    }

    /** Each home_tab_smoke group is a room; a room the server doesn't know at all is left out. */
    private static Smoke smoke() {
        Smoke s = new Smoke();
        for (Group g : SMOKE) {
            boolean known = false, off = false, bad = false;
            for (String e : g.entities) {
                State st = STATES.get(e);
                if (st == null || st.absent) continue;
                known = true;
                if (offline(st.state)) off = true;
                else if (alarming(e, st.state)) bad = true;
            }
            if (!known) continue;
            s.rooms++;
            if (bad) s.alarm++;
            else if (off) s.offline++;
        }
        return s;
    }

    private static boolean alarming(String entity, String state) {
        if (entity.startsWith("binary_sensor.")) return state.equals("on");
        return !(state.equals("off") || state.equals("clear") || state.equals("idle") || state.equals("ok")
                || state.equals("normal") || state.equals("safe"));
    }

    // MARK: - Helpers

    private static State first(List<Group> list) {
        if (list.isEmpty() || list.get(0).entities.length == 0) return null;
        State st = STATES.get(list.get(0).entities[0]);
        return st == null || st.absent ? null : st;
    }

    /** The first entity's state, or null when there is none (not configured, not read, or unknown to the server). */
    private static String groupState(List<Group> list) {
        State st = first(list);
        return st == null ? null : st.state;
    }

    private static int number(Group g, int i) {
        if (g.entities.length <= i) return 0;
        State st = STATES.get(g.entities[i]);
        if (st == null || st.absent) return 0;
        try {
            return (int) Math.round(Double.parseDouble(st.state));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean offline(String state) {
        return state.isEmpty() || state.equals("unavailable") || state.equals("unknown");
    }

    /** "locked", "unlocked", "closed", "open", as Home Assistant says them. */
    private static String word(String state) {
        return state.replace('_', ' ');
    }

    private static String ago(long at, long now) {
        long min = Math.max(0, (now - at) / 60_000);
        if (min < 1) return "just now";
        if (min < 60) return fmt("{n} min ago", "n", min);
        if (min < 1440) return fmt("{n} h ago", "n", Math.round(min / 60f));
        return fmt("{n} d ago", "n", Math.round(min / 1440f));
    }

    /** A pattern with named placeholders, like Ui.trf but never looked up in the catalogues. */
    static String fmt(String pattern, Object... nameValue) {
        String out = pattern;
        for (int i = 0; i + 1 < nameValue.length; i += 2) out = out.replace("{" + nameValue[i] + "}", String.valueOf(nameValue[i + 1]));
        return out;
    }

    private static String count(int n, String one, String other) {
        return n + " " + (n == 1 ? one : other);
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) b.append(b.length() > 0 ? " · " : "").append(p);
        return b.toString();
    }

    /** An ISO time from Home Assistant ("2026-10-31T21:40:00+00:00"), in ms; 0 when it isn't one. */
    private static long time(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // MARK: - The settings

    /** "Name=a+b, Name=c" (a name is optional: "c" alone is named after the entity). Bad ids are left out. */
    static List<Group> parse(String setting) {
        List<Group> out = new ArrayList<>();
        for (String raw : setting.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            int eq = part.lastIndexOf('=');
            String name = eq < 0 ? "" : part.substring(0, eq).trim();
            List<String> ids = new ArrayList<>();
            for (String id : part.substring(eq + 1).split("\\+")) {
                String e = id.trim().toLowerCase(Locale.ROOT);
                if (e.matches("[a-z0-9_]+\\.[a-z0-9_]+")) ids.add(e);
                else if (!e.isEmpty()) AppLog.w("home tab: " + e + " isn't an entity id");
            }
            if (ids.isEmpty()) continue;
            if (name.isEmpty()) name = ids.get(0).substring(ids.get(0).indexOf('.') + 1).replace('_', ' ');
            out.add(new Group(name, ids.toArray(new String[0])));
        }
        return Collections.unmodifiableList(out);
    }

    /** Light rows hold lights only (anyone in the room can press a TV remote), at most MAX_LIGHTS. */
    private static List<Group> lights(List<Group> groups) {
        List<Group> out = new ArrayList<>();
        for (Group g : groups) {
            boolean ok = true;
            for (String e : g.entities) ok &= e.startsWith("light.");
            if (!ok) {
                AppLog.w("home tab: " + g.name + " left out (lights only)");
                continue;
            }
            if (out.size() == MAX_LIGHTS) break;
            out.add(g);
        }
        return Collections.unmodifiableList(out);
    }
}
