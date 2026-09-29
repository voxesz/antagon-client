package studio.antagon;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
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

    private static volatile String windowTitle = "off";
    private static volatile String lastTitle = "", lastArtist = "";
    private static Process watcher;

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
        watchWindow();
        String title = windowTitle;
        if (title.equals("off")) return new String[] {"off"};
        int dash = title.indexOf(" - ");
        if (dash > 0) {
            lastArtist = title.substring(0, dash);
            lastTitle = title.substring(dash + 3);
            return new String[] {"playing", lastTitle, lastArtist, "", "0", "0", "-1"};
        }
        return new String[] {"paused", lastTitle, lastArtist, "", "0", "0", "-1"};
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
        command("playpause", 179);
    }

    static void next() {
        command("next track", 176);
    }

    static void previous() {
        command("previous track", 177);
    }

    static void pause(boolean playing) {
        if (MAC) command("pause", 0);
        else if (playing) command("", 179);
    }

    static void volume(int value) {
        if (MAC) command("set sound volume to " + Math.max(0, Math.min(100, value)), 0);
    }

    static float number(String s) {
        try {
            return Float.parseFloat(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void command(String appleScript, int mediaKey) {
        if (MAC)
            async(() -> osa(RUNNING, "tell application \"Spotify\" to " + appleScript, "end if"));
        else
            async(
                    () ->
                            run(
                                    "powershell",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-Command",
                                    "(New-Object -ComObject WScript.Shell).SendKeys([char]"
                                            + mediaKey
                                            + ")"));
    }

    private static synchronized void watchWindow() {
        if (watcher != null) return;
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        String script =
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                        + "while(Get-Process -Id "
                        + pid
                        + " -EA 0){$p=Get-Process Spotify -EA"
                        + " 0;if(!$p){'off'}else{$t=($p|?{$_.MainWindowTitle}|select -First"
                        + " 1).MainWindowTitle;if($t){$t}else{'paused'}};Start-Sleep 1}";
        try {
            watcher =
                    new ProcessBuilder(
                                    "powershell",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-Command",
                                    script)
                            .redirectErrorStream(true)
                            .start();
            final BufferedReader in =
                    new BufferedReader(new InputStreamReader(watcher.getInputStream(), "UTF-8"));
            async(
                    () -> {
                        try {
                            for (String line; (line = in.readLine()) != null; )
                                windowTitle = line.trim();
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
