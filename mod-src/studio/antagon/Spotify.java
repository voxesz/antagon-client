package studio.antagon;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Spotify {
    static final String SPOTIFY_ON = "if application \"Spotify\" is running then";

    static String run(String... cmd) {
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

    static String osa(String... lines) {
        List<String> cmd = new ArrayList<String>();
        cmd.add("/usr/bin/osascript");
        for (String l : lines) {
            cmd.add("-e");
            cmd.add(l);
        }
        return run(cmd.toArray(new String[0]));
    }

    static void osaAsync(final String... lines) {
        Thread t = new Thread(() -> osa(lines), "Antagon Radio command");
        t.setDaemon(true);
        t.start();
    }

    static String spotifyUri(String link) {
        Matcher m =
                Pattern.compile(
                                "(?:open\\.spotify\\.com/(?:intl-[a-z-]+/)?|spotify:)(playlist|album|track)[/:]([A-Za-z0-9]{22})")
                        .matcher(link == null ? "" : link);
        return m.find() ? "spotify:" + m.group(1) + ":" + m.group(2) : null;
    }

    static float parseNumber(String s) {
        try {
            return Float.parseFloat(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return 0;
        }
    }
}
