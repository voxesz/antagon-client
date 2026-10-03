package studio.antagon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** Audio is played by the launcher. Minecraft only sends controls and reads HUD metadata. */
final class Radio {
    private static final File STATE = new File("antagon-radio-state.properties");
    private static final AtomicLong SEQUENCE = new AtomicLong();

    static Properties status() {
        Properties p = new Properties();
        try (Reader reader = new InputStreamReader(new FileInputStream(STATE), StandardCharsets.UTF_8)) {
            p.load(reader);
            if (System.currentTimeMillis() - Long.parseLong(p.getProperty("updated", "0")) > 5000) p.clear();
        } catch (Exception ignored) { p.clear(); }
        return p;
    }

    static boolean isArt(String filename) {
        return filename != null && filename.matches("antagon-radio-art-[a-f0-9]{16}\\.png");
    }

    static void command(String action) { command(action, ""); }

    static void command(String action, String value) {
        if (!action.matches("toggle|next|previous|stop|volume")) return;
        String id = Long.toString(System.currentTimeMillis()) + String.format("%06d", SEQUENCE.getAndIncrement() % 1000000);
        Path temporary = new File("antagon-radio-cmd-" + id + ".tmp").toPath();
        Path target = new File("antagon-radio-cmd-" + id + ".txt").toPath();
        try {
            Files.write(temporary, (action + "\t" + value).getBytes(StandardCharsets.UTF_8));
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target); }
        } catch (Exception ignored) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignoredAgain) {}
        }
    }
}
