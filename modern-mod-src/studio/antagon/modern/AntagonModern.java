package studio.antagon.modern;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Input;

public final class AntagonModern implements ClientModInitializer {
    public static KeyMapping toggleSprint;
    private static boolean sprintEnabled;
    private static Path config;

    public void onInitializeClient() {
        Minecraft client = Minecraft.getInstance();
        config = client.gameDirectory.toPath().resolve("config/antagon-modern.properties");
        Properties settings = new Properties();
        try (var input = Files.newInputStream(config)) {
            settings.load(input);
            sprintEnabled = Boolean.parseBoolean(settings.getProperty("toggleSprint", "false"));
        } catch (java.io.IOException ignored) { }
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("antagon", "controls"));
        toggleSprint = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.antagon.toggle_sprint", InputConstants.KEY_V, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleSprint.consumeClick()) {
                if (mc.player == null || mc.gui.screen() != null) continue;
                setSprintEnabled(!sprintEnabled);
                mc.player.sendOverlayMessage(Component.translatable(
                    sprintEnabled ? "antagon.sprint.enabled" : "antagon.sprint.disabled"));
            }
            CapeBridge.tick(mc);
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR,
            Identifier.fromNamespaceAndPath("antagon", "toggle_sprint"), (graphics, delta) -> {
                if (sprintEnabled && client.player != null) {
                    graphics.text(client.font, Component.translatable("antagon.sprint.hud"),
                        8, graphics.guiHeight() - 38, 0xFFFFFFFF, true);
                }
            });
        System.out.println("[ANTAGON] Fabric capes and Toggle Sprint ready");
    }

    public static boolean sprintEnabled() { return sprintEnabled; }

    public static void setSprintEnabled(boolean enabled) {
        sprintEnabled = enabled;
        try {
            Files.createDirectories(config.getParent());
            CapeBridge.atomicWrite(config, "toggleSprint=" + enabled + "\n");
        } catch (java.io.IOException error) {
            System.err.println("[ANTAGON] Could not save Toggle Sprint preference: " + error.getMessage());
        }
    }

    // Feed the standard input pipeline so hunger, collisions and server rules keep working normally.
    public static Input sprintInput(Input input) {
        Minecraft client = Minecraft.getInstance();
        if (!sprintEnabled || client.player == null || client.gui.screen() != null
            || !input.forward() || input.backward() || input.shift()) return input;
        return new Input(input.forward(), input.backward(), input.left(), input.right(),
            input.jump(), input.shift(), true);
    }
}
