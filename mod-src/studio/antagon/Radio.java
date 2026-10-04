package studio.antagon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** Native player controls; the legacy file bridge only stops overlapping launcher audio. */
final class Radio {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    static Properties status() {
        return NativeRadio.status();
    }

    static boolean isArt(String filename) {
        return filename != null && filename.matches("antagon-radio-art-[a-f0-9]{16}\\.png");
    }

    static void command(String action) {
        command(action, "");
    }

    static void command(String action, String value) {
        if (action.equals("toggle")) NativeRadio.toggle();
        else if (action.equals("next")) NativeRadio.next(1);
        else if (action.equals("previous")) NativeRadio.next(-1);
        else if (action.equals("stop")) NativeRadio.stop();
        else if (action.equals("volume")) {
            try {
                NativeRadio.volume(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    static void launcherCommand(String action, String value) {
        if (!action.matches("toggle|next|previous|stop|volume")) return;
        String id =
                Long.toString(System.currentTimeMillis())
                        + String.format("%06d", SEQUENCE.getAndIncrement() % 1000000);
        Path temporary = new File("antagon-radio-cmd-" + id + ".tmp").toPath();
        Path target = new File("antagon-radio-cmd-" + id + ".txt").toPath();
        try {
            Files.write(temporary, (action + "\t" + value).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
        } catch (Exception ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredAgain) {
            }
        }
    }
}
