package studio.antagon;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

final class Platform {
    private Platform() {}

    static float number(String value) {
        try { return Float.parseFloat(value.trim().replace(',', '.')); }
        catch (Exception ignored) { return 0; }
    }

    static String clipboard() {
        try {
            Process process = System.getProperty("os.name", "").startsWith("Mac")
                ? new ProcessBuilder("/usr/bin/pbpaste").start()
                : new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", "Get-Clipboard").start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) > 0 && out.size() < 65536;) out.write(buffer, 0, n);
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy();
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Exception ignored) { return ""; }
    }
}
