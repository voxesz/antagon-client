package studio.antagon;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.imageio.ImageIO;

/** Independent in-game radio: no Electron audio or authenticated credentials are needed. */
final class NativeRadio {
    private static final ScheduledExecutorService tasks =
            Executors.newScheduledThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "Antagon music catalog");
                        t.setDaemon(true);
                        return t;
                    });
    private static final ExecutorService decoder =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "Antagon audio");
                        t.setDaemon(true);
                        return t;
                    });
    private static final Set<String> fetchingArt = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean refreshing = new AtomicBoolean();
    private static final AtomicLong generation = new AtomicLong();
    private static final File CACHE = new File("antagon-radio-cache"),
            SETTINGS = new File("config/antagon-radio.properties");
    static volatile RadioCatalog catalog;
    static volatile RadioCatalog.Collection collection;
    static volatile RadioCatalog.Track track;
    static volatile String state = "off", error = "", catalogError = "";
    static volatile double position;
    static volatile float volume = .65f;
    static volatile boolean wantPlay;
    private static volatile RadioAudio audio;
    private static boolean started;
    private static int index;
    private static String liveKey = "";

    static synchronized void open() {
        if (started) return;
        started = true;
        try (InputStream in = new FileInputStream(SETTINGS)) {
            Properties p = new Properties();
            p.load(in);
            float saved = Float.parseFloat(p.getProperty("volume", "65")) / 100;
            if (Float.isFinite(saved)) volume = Math.max(0, Math.min(1, saved));
        } catch (Exception ignored) {
        }
        tasks.scheduleWithFixedDelay(NativeRadio::refresh, 0, 30, TimeUnit.SECONDS);
        tasks.scheduleWithFixedDelay(NativeRadio::tick, 0, 250, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(NativeRadio::stop, "Antagon audio close"));
    }

    static void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            catalog = RadioCatalog.fetch();
            catalogError = "";
            for (RadioCatalog.Collection c : catalog.collections) art(c.cover);
            synchronized (NativeRadio.class) {
                if (collection != null) {
                    collection = catalog.collection(collection.id);
                    if (collection == null) stop();
                    else if (!collection.live() && track != null) {
                        index = collection.trackIds.indexOf(track.id);
                        if (index < 0) stop();
                    }
                }
            }
        } catch (Exception e) {
            catalogError = "Não foi possível atualizar. Verifique sua conexão.";
        } finally {
            refreshing.set(false);
        }
    }

    static void refreshAsync() {
        open();
        tasks.execute(NativeRadio::refresh);
    }

    static synchronized void select(String id, int chosen) {
        open();
        RadioCatalog c = catalog;
        if (c == null) return;
        RadioCatalog.Collection next = c.collection(id);
        if (next == null || !next.live() && (chosen < 0 || chosen >= next.trackIds.size())) return;
        cancel();
        collection = next;
        index = chosen;
        liveKey = "";
        wantPlay = true;
        error = "";
        Radio.launcherCommand("stop", ""); // Avoid two players if the launcher was already playing.
        if (next.live()) tick();
        else load(c.tracks.get(next.trackIds.get(chosen)), 0, "");
    }

    static synchronized void toggle() {
        if (collection == null) return;
        if (wantPlay) {
            wantPlay = false;
            state = "paused";
        } else {
            wantPlay = true;
            error = "";
            if (collection.live()) {
                cancel();
                liveKey = "";
                tick();
            } else if (audio == null) load(track, position, "");
            else state = "playing";
        }
    }

    static synchronized void next(int delta) {
        if (collection == null || collection.live() || collection.trackIds.isEmpty()) return;
        select(
                collection.id,
                (index + delta + collection.trackIds.size()) % collection.trackIds.size());
    }

    static synchronized void seek(double value) {
        if (collection != null && !collection.live() && track != null && Double.isFinite(value)) {
            boolean play = wantPlay;
            load(track, Math.max(0, Math.min(track.duration / 1000d - .05, value)), "");
            wantPlay = play;
            if (!play) state = "paused";
        }
    }

    static synchronized void stop() {
        cancel();
        wantPlay = false;
        collection = null;
        track = null;
        liveKey = "";
        state = "off";
        error = "";
        position = 0;
    }

    private static void cancel() {
        generation.incrementAndGet();
        RadioAudio previous = audio;
        audio = null;
        if (previous != null) previous.close();
    }

    static void volume(int value) {
        volume = Math.max(0, Math.min(100, value)) / 100f;
        tasks.execute(
                () -> {
                    synchronized (SETTINGS) {
                        try {
                            SETTINGS.getParentFile().mkdirs();
                            try (OutputStream out = new FileOutputStream(SETTINGS)) {
                                Properties p = new Properties();
                                p.setProperty("volume", Integer.toString(Math.round(volume * 100)));
                                p.store(out, "Antagon radio");
                            }
                        } catch (IOException ignored) {
                        }
                    }
                });
    }

    private static synchronized void tick() {
        try {
            if (!wantPlay || collection == null || !collection.live() || catalog == null) return;
            if (!catalog.fresh()) {
                cancel();
                wantPlay = false;
                state = "error";
                error = "Sem conexão. Atualize para voltar ao vivo.";
                return;
            }
            RadioCatalog.Live live = catalog.live(collection);
            if (live == null) {
                cancel();
                state = "waiting";
                track = null;
                liveKey = "";
                return;
            }
            if (!live.key.equals(liveKey)
                    || state.equals("playing") && Math.abs(position - live.offset) > 1.5)
                load(live.track, live.offset, live.key);
        } catch (Exception e) {
            state = "error";
            error = "Não foi possível iniciar a rádio.";
            wantPlay = false;
            cancel();
        }
    }

    private static void load(RadioCatalog.Track selected, double offset, String key) {
        cancel();
        track = selected;
        position = offset;
        liveKey = key;
        state = "buffering";
        error = "";
        if (selected == null) {
            state = "waiting";
            return;
        }
        if (selected.media.endsWith(".m4a")) {
            state = "error";
            error = "Esta faixa M4A está disponível no launcher.";
            wantPlay = false;
            return;
        }
        final long token = generation.get();
        RadioAudio player = new RadioAudio();
        audio = player;
        art(selected.cover.isEmpty() && collection != null ? collection.cover : selected.cover);
        decoder.execute(
                () -> {
                    try {
                        if (generation.get() != token) return;
                        File file = cache(selected, token);
                        if (generation.get() != token) return;
                        player.play(
                                file,
                                new RadioAudio.Listener() {
                                    public boolean current() {
                                        return generation.get() == token;
                                    }

                                    public boolean paused() {
                                        return !wantPlay;
                                    }

                                    public float volume() {
                                        return NativeRadio.volume;
                                    }

                                    public double target() {
                                        RadioCatalog c = catalog;
                                        RadioCatalog.Collection selectedCollection = collection;
                                        RadioCatalog.Live live =
                                                c != null
                                                                && selectedCollection != null
                                                                && selectedCollection.live()
                                                        ? c.live(selectedCollection)
                                                        : null;
                                        return live != null && live.key.equals(key)
                                                ? live.offset
                                                : offset;
                                    }

                                    public void progress(double at) {
                                        if (current()) {
                                            position = at;
                                            state = wantPlay ? "playing" : "paused";
                                        }
                                    }
                                });
                        synchronized (NativeRadio.class) {
                            if (generation.get() != token) return;
                            audio = null;
                            if (collection != null && !collection.live()) {
                                if (wantPlay && index + 1 < collection.trackIds.size()) next(1);
                                else {
                                    wantPlay = false;
                                    state = "paused";
                                    position = selected.duration / 1000d;
                                }
                            }
                        }
                    } catch (Exception e) {
                        synchronized (NativeRadio.class) {
                            if (generation.get() == token) {
                                audio = null;
                                state = "error";
                                error =
                                        "Não foi possível tocar. Confira o áudio do sistema e tente"
                                            + " novamente.";
                                wantPlay = false;
                            }
                        }
                        if (generation.get() == token)
                            System.err.println(
                                    "[ANTAGON] Radio playback: "
                                            + e.getClass().getSimpleName()
                                            + ": "
                                            + e.getMessage());
                    } finally {
                        player.close();
                    }
                });
    }

    private static File cache(RadioCatalog.Track t, long token) throws Exception {
        CACHE.mkdirs();
        File target = new File(CACHE, t.media.substring(7));
        if (target.isFile() && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis());
            return target;
        }
        HttpURLConnection connection =
                (HttpURLConnection) new URL(RadioCatalog.STORAGE + t.media).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        File temp = new File(CACHE, target.getName() + ".part");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Faixa indisponível");
            try (InputStream in = connection.getInputStream();
                    OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int n, total = 0;
                while ((n = in.read(buffer)) >= 0) {
                    if (generation.get() != token)
                        throw new InterruptedIOException("Seleção alterada");
                    total += n;
                    if (total > 50 * 1024 * 1024) throw new IOException("Faixa muito grande");
                    out.write(buffer, 0, n);
                }
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temp.toPath());
        }
        File[] entries = CACHE.listFiles(f -> !f.getName().endsWith(".part"));
        if (entries != null) {
            Arrays.sort(entries, Comparator.comparingLong(File::lastModified));
            long total = 0;
            for (File f : entries) total += f.length();
            for (File f : entries)
                if (total > 256L * 1024 * 1024 && !f.equals(target)) {
                    long size = f.length();
                    if (f.delete()) total -= size;
                }
        }
        return target;
    }

    static String art(String key) {
        if (key == null || !key.matches("covers/[a-f0-9-]{36}\\.png")) return "";
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest((RadioCatalog.STORAGE + key).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            String filename = "antagon-radio-art-" + hex + ".png";
            File target = new File(filename);
            if (target.isFile()) return filename;
            if (fetchingArt.add(key))
                tasks.execute(
                        () -> {
                            try {
                                URLConnection c =
                                        new URL(RadioCatalog.STORAGE + key).openConnection();
                                c.setConnectTimeout(6000);
                                c.setReadTimeout(6000);
                                BufferedImage image =
                                        ImageIO.read(
                                                new ByteArrayInputStream(
                                                        RadioCatalog.read(
                                                                c.getInputStream(),
                                                                2 * 1024 * 1024)));
                                if (image == null
                                        || image.getWidth() > 1024
                                        || image.getHeight() > 1024) return;
                                BufferedImage scaled =
                                        new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                                java.awt.Graphics2D g = scaled.createGraphics();
                                g.drawImage(image, 0, 0, 64, 64, null);
                                g.dispose();
                                File tmp = new File(filename + ".tmp");
                                ImageIO.write(scaled, "png", tmp);
                                Files.move(
                                        tmp.toPath(),
                                        target.toPath(),
                                        StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception ignored) {
                            } finally {
                                fetchingArt.remove(key);
                            }
                        });
        } catch (Exception ignored) {
        }
        return "";
    }

    static Properties status() {
        Properties p = new Properties();
        RadioCatalog.Track t = track;
        RadioCatalog.Collection c = collection;
        p.setProperty("state", state);
        p.setProperty("title", t == null ? "" : t.title);
        p.setProperty("artist", t == null ? "" : t.artist);
        p.setProperty("collection", c == null ? "" : c.name);
        p.setProperty("mode", c == null ? "" : c.mode);
        p.setProperty("position", Double.toString(position));
        p.setProperty("duration", t == null ? "0" : Double.toString(t.duration / 1000d));
        p.setProperty("volume", Integer.toString(Math.round(volume * 100)));
        p.setProperty(
                "art", art(t != null && !t.cover.isEmpty() ? t.cover : c == null ? "" : c.cover));
        return p;
    }

    private NativeRadio() {}
}
