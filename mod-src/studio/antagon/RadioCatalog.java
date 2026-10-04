package studio.antagon;

import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public, read-only music catalog. Schedules use the sampled server clock. */
final class RadioCatalog {
    static final String ROOT = "https://pnlemlqvuqftantunwcw.supabase.co";
    static final String PUBLIC_KEY = "sb_publishable_cavp7w5y09iRpEOq5F3x5Q_GE4Uwz-X";
    static final String STORAGE = ROOT + "/storage/v1/object/public/radio-media/";
    final Map<String, Track> tracks = new LinkedHashMap<String, Track>();
    final List<Collection> collections = new ArrayList<Collection>();
    final long sampledAt;
    final double serverTime;

    static final class Track {
        final String id, title, artist, media, cover;
        final long duration;

        Track(JsonObject o) {
            id = text(o, "id");
            title = text(o, "title");
            artist = text(o, "artist");
            media = text(o, "mediaKey");
            cover = text(o, "coverKey");
            duration = o.get("durationMs").getAsLong();
            if (!media.matches("tracks/[a-f0-9-]{36}\\.(mp3|m4a|ogg|wav)")
                    || duration < 1000
                    || duration > 3600000) throw new IllegalArgumentException("Invalid track");
        }
    }

    static final class Collection {
        final String id, name, genre, mode, cover;
        final List<String> trackIds;
        final List<Schedule> schedules = new ArrayList<Schedule>();

        Collection(JsonObject o) {
            id = text(o, "id");
            name = text(o, "name");
            genre = text(o, "genre");
            mode = text(o, "mode");
            cover = text(o, "coverKey");
            trackIds = ids(o.getAsJsonArray("trackIds"));
            for (JsonElement e : o.getAsJsonArray("schedules"))
                schedules.add(new Schedule(e.getAsJsonObject()));
        }

        boolean live() {
            return mode.equals("radio");
        }
    }

    static final class Schedule {
        final long start;
        final List<String> ids;

        Schedule(JsonObject o) {
            start = o.get("startsAt").getAsLong();
            ids = ids(o.getAsJsonArray("trackIds"));
        }
    }

    static final class Live {
        final Track track;
        final String key;
        final double offset;

        Live(Track t, String k, double o) {
            track = t;
            key = k;
            offset = o;
        }
    }

    RadioCatalog(JsonObject json, long elapsed) {
        sampledAt = System.nanoTime();
        serverTime = json.get("serverTime").getAsDouble() + elapsed / 2e6;
        for (JsonElement e : json.getAsJsonArray("tracks")) {
            Track t = new Track(e.getAsJsonObject());
            tracks.put(t.id, t);
        }
        for (JsonElement e : json.getAsJsonArray("collections"))
            collections.add(new Collection(e.getAsJsonObject()));
    }

    Collection collection(String id) {
        for (Collection c : collections) if (c.id.equals(id)) return c;
        return null;
    }

    double now() {
        return serverTime + (System.nanoTime() - sampledAt) / 1e6;
    }

    boolean fresh() {
        return System.nanoTime() - sampledAt < 90000000000L;
    }

    Live live(Collection collection) {
        return live(collection, now());
    }

    Live live(Collection c, double now) {
        Schedule schedule = null;
        for (Schedule s : c.schedules)
            if (s.start <= now && (schedule == null || s.start > schedule.start)) schedule = s;
        if (schedule == null || schedule.ids.isEmpty()) return null;
        long total = 0;
        for (String id : schedule.ids) {
            Track t = tracks.get(id);
            if (t == null) return null; // Dropping a missing track would desynchronize listeners.
            total += t.duration;
        }
        long cycle = (long) Math.floor((now - schedule.start) / total);
        double offset = (now - schedule.start) % total;
        for (int i = 0; i < schedule.ids.size(); i++) {
            Track t = tracks.get(schedule.ids.get(i));
            if (offset < t.duration)
                return new Live(t, schedule.start + ":" + cycle + ":" + i, offset / 1000);
            offset -= t.duration;
        }
        return null;
    }

    static RadioCatalog fetch() throws Exception {
        long start = System.nanoTime();
        HttpURLConnection connection =
                (HttpURLConnection) new URL(ROOT + "/rest/v1/rpc/radio_catalog").openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("apikey", PUBLIC_KEY);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) throw new IOException("Catálogo indisponível");
            byte[] data = read(connection.getInputStream(), 2 * 1024 * 1024);
            long elapsed = System.nanoTime() - start;
            if (elapsed > 10000000000L) throw new IOException("Conexão lenta");
            return new RadioCatalog(
                    new JsonParser()
                            .parse(new String(data, StandardCharsets.UTF_8))
                            .getAsJsonObject(),
                    elapsed);
        } finally {
            connection.disconnect();
        }
    }

    static byte[] read(InputStream source, int maximum) throws IOException {
        try (InputStream in = source;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (out.size() + n > maximum) throw new IOException("Arquivo muito grande");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static List<String> ids(JsonArray values) {
        List<String> ids = new ArrayList<String>();
        for (JsonElement e : values) ids.add(e.getAsString());
        return ids;
    }
}
