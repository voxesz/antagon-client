package studio.antagon;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Spotify {
    static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");
    static final boolean HAS_VOLUME = MAC;

    private static final String RUNNING = "if application \"Spotify\" is running then";
    private static final Pattern LINK =
            Pattern.compile(
                    "(?:open\\.spotify\\.com/(?:intl-[a-z-]+/)?|spotify:)(playlist|album|track)[/:]([A-Za-z0-9]{22})");

    private static final Pattern JAM =
            Pattern.compile(
                    "https://(?:open\\.spotify\\.com/socialsession/([A-Za-z0-9]+)|spotify\\.link/([A-Za-z0-9]+))");

    private static final List<String> STATES = Arrays.asList("off", "stopped", "playing", "paused");
    private static volatile String[] windowsStatus = {"off"};
    private static Process watcher;
    private static File commandFile, artDir;

    private Spotify() {}

    static String uri(String link) {
        Matcher m = LINK.matcher(link == null ? "" : link);
        return m.find() ? "spotify:" + m.group(1) + ":" + m.group(2) : null;
    }

    static String jam(String link) {
        Matcher m = JAM.matcher(link == null ? "" : link);
        if (!m.find()) return null;
        return m.group(1) != null
                ? "https://open.spotify.com/socialsession/" + m.group(1)
                : "https://spotify.link/" + m.group(2);
    }

    static void open(String link) {
        String url = jam(link);
        if (url == null) return;
        if (MAC) async(() -> run("/usr/bin/open", url));
        else async(() -> run("rundll32", "url.dll,FileProtocolHandler", url));
    }

    static String clipboard() {
        return MAC
                ? run("/usr/bin/pbpaste")
                : run("powershell", "-NoProfile", "-NonInteractive", "-Command", "Get-Clipboard");
    }

    static String[] status() {
        if (MAC)
            return osa(
                            RUNNING,
                            "tell application \"Spotify\"",
                            "if player state is stopped then return \"stopped\"",
                            "set t to current track",
                            "return (player state as string) & \"|~|\" & (name of t) & \"|~|\" &"
                                    + " (artist of t) & \"|~|\" & (artwork url of t) & \"|~|\" &"
                                    + " (player position as string) & \"|~|\" & (duration of t as"
                                    + " string) & \"|~|\" & (sound volume as string)",
                            "end tell",
                            "end if",
                            "return \"off\"")
                    .split("\\|~\\|");
        watchMediaSession();
        return windowsStatus;
    }

    static boolean isArt(String path) {
        File dir = artDir;
        return dir != null && path.startsWith(dir.getPath() + File.separator);
    }

    static void play(String uri) {
        if (uri(uri) == null) return;
        if (MAC)
            async(
                    () ->
                            osa(
                                    "if application \"Spotify\" is not running then",
                                    "do shell script \"open -g -a Spotify\"",
                                    "delay 4",
                                    "end if",
                                    "tell application \"Spotify\" to play track \""
                                            + uri(uri)
                                            + "\""));
        else async(() -> run("cmd", "/c", "start", "", uri(uri)));
    }

    static void playPause() {
        command("playpause", "playpause");
    }

    static void next() {
        command("next track", "next");
    }

    static void previous() {
        command("previous track", "previous");
    }

    static void pause(boolean playing) {
        if (MAC || playing) command("pause", "pause");
    }

    static void volume(int value) {
        if (MAC) command("set sound volume to " + Math.max(0, Math.min(100, value)), null);
    }

    static float number(String s) {
        try {
            return Float.parseFloat(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void command(String appleScript, String windowsCommand) {
        if (MAC) {
            async(() -> osa(RUNNING, "tell application \"Spotify\" to " + appleScript, "end if"));
            return;
        }
        if (windowsCommand == null) return;
        watchMediaSession();
        try {
            Files.write(commandFile.toPath(), windowsCommand.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static synchronized void watchMediaSession() {
        if (watcher != null) return;
        try {
            artDir = Files.createTempDirectory("antagon-radio").toFile();
            commandFile = new File(artDir, "command.txt");
            File script = new File(artDir, "radio.ps1");
            try (InputStream in =
                    Spotify.class.getResourceAsStream("/assets/antagon/radio-windows.ps1")) {
                Files.copy(in, script.toPath());
            }
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            watcher =
                    new ProcessBuilder(
                                    "powershell",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-ExecutionPolicy",
                                    "Bypass",
                                    "-File",
                                    script.getPath(),
                                    pid,
                                    commandFile.getPath(),
                                    artDir.getPath())
                            .redirectErrorStream(true)
                            .start();
            final BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(
                                    watcher.getInputStream(), StandardCharsets.UTF_8));
            async(
                    () -> {
                        try {
                            for (String line; (line = in.readLine()) != null; ) {
                                String[] status = line.trim().split("\\|~\\|");
                                if (STATES.contains(status[0])) windowsStatus = status;
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            watcher = null;
        }
    }

    private static void async(Runnable task) {
        Thread thread = new Thread(task, "Antagon Radio");
        thread.setDaemon(true);
        thread.start();
    }

    private static String osa(String... lines) {
        List<String> cmd = new ArrayList<String>();
        cmd.add("/usr/bin/osascript");
        for (String line : lines) {
            cmd.add("-e");
            cmd.add(line);
        }
        return run(cmd.toArray(new String[0]));
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            InputStream in = p.getInputStream();
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            p.waitFor();
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
