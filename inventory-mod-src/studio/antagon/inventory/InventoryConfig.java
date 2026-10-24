package studio.antagon.inventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Host-owned preferences. Only a bounded radius is passed to the server task. */
public final class InventoryConfig {
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 64;
    public static final int DEFAULT_RADIUS = 8;
    private final Path file;
    private final Properties properties = new Properties();
    private int radius = DEFAULT_RADIUS;

    public InventoryConfig(Path file) {
        this.file = file;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            radius = clamp(Integer.parseInt(properties.getProperty("storageRadius", Integer.toString(DEFAULT_RADIUS)).trim()));
        } catch (IOException | IllegalArgumentException ignored) { }
    }
    public static int clamp(int radius) { return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius)); }
    public int storageRadius() { return radius; }
    public void setStorageRadius(int value) throws IOException {
        int next = clamp(value);
        Properties updated = new Properties();
        updated.putAll(properties);
        updated.setProperty("storageRadius", Integer.toString(next));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "antagon-inventory-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                updated.store(writer, "Antagon Inventory - alcance dos baús");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            properties.clear(); properties.putAll(updated);
            radius = next;
        } finally { Files.deleteIfExists(temporary); }
    }
}
