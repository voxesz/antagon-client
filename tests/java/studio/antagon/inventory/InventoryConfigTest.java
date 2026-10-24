package studio.antagon.inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class InventoryConfigTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("antagon-inventory-config-test-");
        try {
            Path file = directory.resolve("config/antagon-inventory.properties");
            InventoryConfig defaults = new InventoryConfig(file);
            check(defaults.storageRadius() == 8, "Wrong default radius");
            defaults.setStorageRadius(32);
            check(new InventoryConfig(file).storageRadius() == 32, "Radius did not persist");
            defaults.setStorageRadius(Integer.MAX_VALUE);
            check(defaults.storageRadius() == 64 && new InventoryConfig(file).storageRadius() == 64, "Upper bound failed");
            defaults.setStorageRadius(-10);
            check(defaults.storageRadius() == 1 && new InventoryConfig(file).storageRadius() == 1, "Lower bound failed");
            Files.writeString(file, "storageRadius=invalid\notherSetting=keep\n");
            InventoryConfig corrupt = new InventoryConfig(file);
            check(corrupt.storageRadius() == 8, "Invalid value did not fall back to the default");
            corrupt.setStorageRadius(12);
            check(new InventoryConfig(file).storageRadius() == 12 && Files.readString(file).contains("otherSetting=keep"),
                "Saving discarded unrelated preferences");
            check(InventoryConfig.clamp(Integer.MIN_VALUE) == 1 && InventoryConfig.clamp(Integer.MAX_VALUE) == 64,
                "Server request bounds failed");
            try (var files = Files.list(file.getParent())) {
                check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "A temporary file was left behind");
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("OK: configurable radius, defaults, persistence, invalid values, preserved preferences and server bounds.");
    }
}
