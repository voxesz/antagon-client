package studio.antagon.modern;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/** Reads only public equipment and local textures. Account credentials stay in the launcher. */
public final class CapeBridge {
    private static final Map<String, String> equipped = new HashMap<>();
    private static final Map<String, ClientAsset.Texture> textures = new HashMap<>();
    private static final Map<String, Long> textureTimes = new HashMap<>();
    private static long lastPoll;
    private static String lastRoster;
    private static String lastStatus;
    private static Path game;

    private CapeBridge() { }

    public static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastPoll < 250) return;
        lastPoll = now;
        game = client.gameDirectory.toPath();
        try {
            Path snapshot = game.resolve("antagon-cosmetics.properties");
            equipped.clear();
            if (Files.isRegularFile(snapshot) && Files.size(snapshot) <= 65536
                && now - Files.getLastModifiedTime(snapshot).toMillis() <= 120000) {
                Properties properties = new Properties();
                try (var input = Files.newInputStream(snapshot)) { properties.load(input); }
                for (String id : properties.stringPropertyNames()) {
                    if (!id.matches("[0-9a-f]{32}")) continue;
                    String[] tokens = properties.getProperty(id).split(",");
                    if (!java.util.Arrays.asList(tokens).contains("client")) continue;
                    for (String token : tokens) {
                        if (!token.startsWith("cape:")) continue;
                        String item = token.substring(5);
                        if (item.matches("antagon_cape|antagon_logo_cape|custom_[a-f0-9]{16}"))
                            equipped.put(id, item);
                    }
                }
            }
            var required = new HashSet<>(equipped.values());
            for (String old : new HashSet<>(textures.keySet())) {
                if (required.contains(old)) continue;
                if (old.startsWith("custom_")) client.getTextureManager().release(textures.get(old).texturePath());
                textures.remove(old);
                textureTimes.remove(old);
            }
            for (String item : required) loadTexture(client, item);
            String roster = client.getConnection() == null ? "" : client.getConnection().getOnlinePlayerIds().stream()
                .map(id -> id.toString().replace("-", "")).sorted().limit(100).collect(java.util.stream.Collectors.joining("\n"));
            if (!roster.equals(lastRoster)) {
                atomicWrite(game.resolve("antagon-players.txt"), roster);
                lastRoster = roster;
            }
            String status = client.level == null ? "menu" : client.getCurrentServer() == null ? "singleplayer"
                : "server " + client.getCurrentServer().ip.replaceAll("[\\s]", "");
            if (!status.equals(lastStatus)) {
                atomicWrite(game.resolve("antagon-status.txt"), status);
                lastStatus = status;
            }
        } catch (IOException error) {
            equipped.clear();
        }
    }

    private static void loadTexture(Minecraft client, String item) {
        Identifier location = Identifier.fromNamespaceAndPath("antagon", "textures/capes/" + item + ".png");
        if (!item.startsWith("custom_")) {
            textures.putIfAbsent(item, new ClientAsset.ResourceTexture(location, location));
            return;
        }
        Path file = game.resolve("antagon-capes").resolve(item + ".png");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 8 * 1024 * 1024) return;
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (textures.containsKey(item) && textureTimes.getOrDefault(item, -1L) == modified) return;
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 24 || ByteBuffer.wrap(bytes).getLong() != 0x89504e470d0a1a0aL) return;
            int width = ByteBuffer.wrap(bytes, 16, 4).getInt();
            int height = ByteBuffer.wrap(bytes, 20, 4).getInt();
            if (width < 64 || width > 2048 || width % 64 != 0 || height != width / 2) return;
            NativeImage image = NativeImage.read(bytes);
            DynamicTexture texture = new DynamicTexture(() -> "Antagon cape " + item, image);
            client.getTextureManager().register(location, texture);
            textures.put(item, new ClientAsset.ResourceTexture(location, location));
            textureTimes.put(item, modified);
        } catch (IOException | RuntimeException error) {
            // The launcher may still be finishing the download; retry on the next poll.
        }
    }

    public static PlayerSkin skin(UUID uuid, PlayerSkin original) {
        String item = equipped.get(uuid.toString().replace("-", ""));
        ClientAsset.Texture cape = item == null ? null : textures.get(item);
        if (cape == null || original == null) return original;
        return new PlayerSkin(original.body(), cape, original.elytra(), original.model(), original.secure());
    }

    public static void atomicWrite(Path target, String text) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, text);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
