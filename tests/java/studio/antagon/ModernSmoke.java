package studio.antagon;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.RemotePlayer;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import studio.antagon.modern.AntagonModern;
import studio.antagon.modern.CapeBridge;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

// Built only into the isolated test profile; never distributed with the client.
public final class ModernSmoke implements ClientModInitializer {
    private int phase;
    private long changed;
    private long diagnostic;
    private int changedTick;
    private RemotePlayer friend;
    private BlockPos schematicOrigin;
    private static final UUID FRIEND = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private boolean cape(net.minecraft.client.player.AbstractClientPlayer player, String item) {
        var texture = player.getSkin().cape();
        return texture != null && texture.texturePath().toString().equals("antagon:textures/capes/" + item + ".png");
    }

    private void equipment(Minecraft mc, String own, String other) {
        try {
            CapeBridge.atomicWrite(mc.gameDirectory.toPath().resolve("antagon-cosmetics.properties"),
                mc.player.getUUID().toString().replace("-", "") + "=client" + (own == null ? "" : ",cape:" + own)
                + "\n" + FRIEND.toString().replace("-", "") + "=client" + (other == null ? "" : ",cape:" + other) + "\n");
        } catch (java.io.IOException error) { throw new RuntimeException(error); }
    }

    public void onInitializeClient() {
        Thread worker = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(200);
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.submit(() -> tick(mc)).get();
                }
            } catch (Throwable error) {
                error.printStackTrace();
                System.out.println("[MODERN TEST] FAILED " + error);
                System.exit(1);
            }
        }, "Antagon modern smoke test");
        worker.setDaemon(true);
        worker.start();
    }

    private void advance() {
        phase++;
        changed = System.currentTimeMillis();
        var player = Minecraft.getInstance().player;
        changedTick = player == null ? 0 : player.tickCount;
    }

    private void screenshot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
            message -> System.out.println("[MODERN TEST] Screenshot " + name + " " + message.getString()));
    }

    private void tick(Minecraft mc) {
        if (!mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
        long elapsed = System.currentTimeMillis() - changed;
        if (phase >= 5 && phase <= 14 && mc.player.tickCount - changedTick < 12) return;
        if (System.currentTimeMillis() - diagnostic > 10000) {
            diagnostic = System.currentTimeMillis();
            System.out.println("[MODERN TEST] Phase=" + phase + "; screen=" + mc.gui.screen());
        }
        if (phase == 0) {
            for (String id : new String[]{"iris", "sodium", "fabric-api", "lithium", "ferritecore", "essential", "antagon_modern", "xaerominimap", "litematica", "malilib"}) {
                if (!FabricLoader.getInstance().isModLoaded(id)) throw new IllegalStateException("Missing " + id);
            }
            System.out.println("[MODERN TEST] Essential loaded with Sodium, Iris, Lithium and FerriteCore");
            mc.gui.setScreen(new TitleScreen());
            advance();
        } else if (phase == 1 && elapsed > 3000) {
            screenshot(mc, "modern-menu");
            mc.gui.setScreen(new ShaderPackScreen(new TitleScreen()));
            advance();
        } else if (phase == 2 && elapsed > 3000) {
            screenshot(mc, "modern-iris");
            System.out.println("[MODERN TEST] Iris shader selection screen opened");
            CreateWorldScreen.testWorld(mc, () -> mc.gui.setScreen(new TitleScreen()));
            advance();
        } else if (phase == 3 && mc.gui.screen() instanceof CreateWorldScreen create) {
            try {
                create.getUiState().setName("Antagon Modern Smoke");
                create.getUiState().setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                var method = CreateWorldScreen.class.getDeclaredMethod("onCreate");
                method.setAccessible(true);
                method.invoke(create);
                advance();
            } catch (ReflectiveOperationException error) {
                throw new RuntimeException(error);
            }
        } else if (phase == 4 && mc.level == null && mc.gui.screen() instanceof ConfirmScreen confirm) {
            // Confirm the game's experimental-world notice in this disposable test profile.
            try {
                var field = ConfirmScreen.class.getDeclaredField("callback");
                field.setAccessible(true);
                ((it.unimi.dsi.fastutil.booleans.BooleanConsumer) field.get(confirm)).accept(true);
            } catch (ReflectiveOperationException error) {
                throw new RuntimeException(error);
            }
        } else if (phase == 4 && mc.level != null && mc.player != null && mc.player.tickCount > 120 && mc.getFps() > 0) {
            if (!IrisApi.getInstance().isShaderPackInUse() || Iris.getStoredError().isPresent())
                throw new IllegalStateException("Shader pipeline failed: " + Iris.getStoredError());
            screenshot(mc, "modern-world-shaders");
            System.out.println("[MODERN TEST] World rendered with " + Iris.getCurrentPackName() + "; FPS=" + mc.getFps());
            require(cape(mc.player, "antagon_cape"), "Own Antagon cape was not applied");
            friend = new RemotePlayer(mc.level, new GameProfile(FRIEND, "AntagonFriend"));
            friend.setId(987654);
            friend.setPos(mc.player.getX() + 2, mc.player.getY(), mc.player.getZ() + 2);
            try {
                var layers = net.minecraft.world.entity.Avatar.class.getDeclaredField("DATA_PLAYER_MODE_CUSTOMISATION");
                layers.setAccessible(true);
                @SuppressWarnings("unchecked")
                var accessor = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) layers.get(null);
                friend.getEntityData().set(accessor, (byte) 127);
            } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            mc.level.addEntity(friend);
            equipment(mc, "antagon_cape", "antagon_logo_cape");
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.player.setXRot(10);
            mc.player.setYRot(0);
            advance();
        } else if (phase == 5 && elapsed > 1000) {
            require(cape(mc.player, "antagon_cape"), "Own cape lost: " + mc.player.getSkin().cape());
            require(cape(friend, "antagon_logo_cape"), "Remote cape missing: uuid=" + friend.getUUID()
                + "; skin=" + friend.getSkin().cape() + "; bridge=" + CapeBridge.skin(friend.getUUID(), friend.getSkin()).cape());
            @SuppressWarnings("rawtypes")
            var renderer = (net.minecraft.client.renderer.entity.player.AvatarRenderer) mc.getEntityRenderDispatcher().getRenderer(friend);
            var renderState = renderer.createRenderState();
            renderer.extractRenderState(friend, renderState, 0);
            require(renderState.showCape && renderState.skin.cape() != null, "Remote cape must reach the render state");
            screenshot(mc, "modern-cape-builtins");
            try {
                Path directory = mc.gameDirectory.toPath().resolve("antagon-capes");
                Files.createDirectories(directory);
                try (var image = CapeBridge.class.getResourceAsStream("/assets/antagon/textures/capes/antagon_logo_cape.png")) {
                    Files.copy(image, directory.resolve("custom_0123456789abcdef.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
            equipment(mc, "custom_0123456789abcdef", "antagon_logo_cape");
            advance();
        } else if (phase == 6 && elapsed > 1000) {
            require(cape(mc.player, "custom_0123456789abcdef"), "Custom cape was not loaded");
            require(mc.getTextureManager().getTexture(mc.player.getSkin().cape().texturePath()) instanceof net.minecraft.client.renderer.texture.DynamicTexture,
                "Custom cape must use an uploaded dynamic texture");
            screenshot(mc, "modern-cape-custom");
            equipment(mc, null, "antagon_logo_cape");
            advance();
        } else if (phase == 7 && elapsed > 750) {
            require(!cape(mc.player, "custom_0123456789abcdef") && cape(friend, "antagon_logo_cape"), "Unequipping a cape must restore the original skin");
            try {
                Files.setLastModifiedTime(mc.gameDirectory.toPath().resolve("antagon-cosmetics.properties"), FileTime.fromMillis(System.currentTimeMillis() - 121000));
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
            advance();
        } else if (phase == 8 && elapsed > 750) {
            require(!cape(friend, "antagon_logo_cape"), "Expired equipment must not keep rendering");
            equipment(mc, "antagon_cape", "antagon_logo_cape");
            KeyMapping.click(AntagonModern.toggleSprint.getDefaultKey());
            advance();
        } else if (phase == 9) {
            mc.options.keyUp.setDown(true);
            if (elapsed < 1500) return;
            require(AntagonModern.sprintEnabled() && mc.player.input.keyPresses.sprint() && mc.player.isSprinting(), "Toggle Sprint must trigger actual sprinting");
            mc.options.keyShift.setDown(true);
            advance();
        } else if (phase == 10 && elapsed > 750) {
            require(mc.player.input.keyPresses.shift() && !mc.player.input.keyPresses.sprint(), "Toggle Sprint must respect sneaking");
            mc.options.keyShift.setDown(false);
            mc.options.keyUp.setDown(false);
            advance();
        } else if (phase == 11 && elapsed > 750) {
            require(AntagonModern.sprintEnabled() && !mc.player.input.keyPresses.sprint(), "Toggle must remain enabled while the player stops");
            KeyMapping.click(AntagonModern.toggleSprint.getDefaultKey());
            mc.options.keyUp.setDown(true);
            advance();
        } else if (phase == 12 && elapsed > 750) {
            require(!AntagonModern.sprintEnabled() && !mc.player.input.keyPresses.sprint(), "Disabling Toggle Sprint must stop supplying sprint input");
            mc.options.keySprint.setDown(true);
            advance();
        } else if (phase == 13 && elapsed > 750) {
            require(mc.player.input.keyPresses.sprint(), "Manual sprint key must keep working");
            mc.options.keySprint.setDown(false);
            mc.options.keyUp.setDown(false);
            KeyMapping.click(AntagonModern.toggleSprint.getDefaultKey());
            advance();
        } else if (phase == 14 && elapsed > 1000) {
            require(AntagonModern.sprintEnabled(), "Sprint toggle did not restore");
            try {
                require(Files.readString(mc.gameDirectory.toPath().resolve("config/antagon-modern.properties")).contains("toggleSprint=true"),
                    "Toggle Sprint preference must be saved");
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
            screenshot(mc, "modern-cape-sprint");
            System.out.println("[MODERN TEST] Antagon capes and Toggle Sprint verified");
            advance();
        } else if (phase == 15 && elapsed > 3000) {
            try {
                Object minimapMod = Class.forName("xaero.common.HudMod").getField("INSTANCE").get(null);
                Object minimap = minimapMod.getClass().getMethod("getMinimap").invoke(minimapMod);
                require(minimap != null && minimap.getClass().getMethod("getCrashedWith").invoke(minimap) == null,
                    "Minimap renderer failed");
                Object helper = minimapMod.getClass().getMethod("getGuiHelper").invoke(minimapMod);
                helper.getClass().getMethod("openMainSettingsFromScreen", net.minecraft.client.gui.screens.Screen.class).invoke(helper, new Object[]{null});
            } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            advance();
        } else if (phase == 16 && elapsed > 2000) {
            require(mc.gui.screen() != null && mc.gui.screen().getClass().getName().startsWith("xaero."), "Minimap settings did not open");
            screenshot(mc, "modern-minimap-settings");
            mc.gui.setScreen(new GuiMainMenu());
            advance();
        } else if (phase == 17 && elapsed > 2000) {
            require(mc.gui.screen() instanceof GuiMainMenu, "Litematica menu did not open");
            screenshot(mc, "modern-litematica-menu");
            mc.gui.setScreen(null);
            try { Iris.toggleShaders(mc, false); }
            catch (java.io.IOException error) { throw new RuntimeException(error); }
            require(!IrisApi.getInstance().isShaderPackInUse(), "Shaders must be disabled for schematic rendering");
            var area = new AreaSelection();
            area.setName("Antagon Smoke");
            area.addSubRegionBox(new Box(BlockPos.ZERO, new BlockPos(2, 2, 0), "wall"), true);
            var schematic = LitematicaSchematic.createEmptySchematic(area, "AntagonTest");
            require(schematic != null, "Test schematic creation failed");
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++)
                schematic.getSubRegionContainer("wall").set(x, y, 0, Blocks.DIAMOND_BLOCK.defaultBlockState());
            Path directory = DataManager.getSchematicsBaseDirectory();
            require(schematic.writeToFile(directory, "antagon-smoke", true), "Schematic save failed");
            var loaded = LitematicaSchematic.createFromFile(directory, "antagon-smoke.litematic");
            require(loaded != null && loaded.getSubRegionContainer("wall").get(1, 1, 0).is(Blocks.DIAMOND_BLOCK), "Saved schematic did not load");
            schematicOrigin = mc.player.blockPosition().offset(-1, 1, 5);
            var placement = SchematicPlacement.createFor(loaded, schematicOrigin, "Antagon Smoke", true, true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, true);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            advance();
        } else if (phase == 18 && elapsed > 5000) {
            require(SchematicWorldHandler.getSchematicWorld() != null &&
                SchematicWorldHandler.getSchematicWorld().getBlockState(schematicOrigin.offset(1, 1, 0)).is(Blocks.DIAMOND_BLOCK),
                "Schematic placement blocks did not reach Litematica world");
            require(!mc.level.getBlockState(schematicOrigin.offset(1, 1, 0)).is(Blocks.DIAMOND_BLOCK), "Schematic must not alter real world blocks");
            screenshot(mc, "modern-litematica-schematic");
            System.out.println("[MODERN TEST] Minimap and Litematica schematic verified");
            System.out.println("[MODERN TEST] OK: menu, Iris screen, world, minimap and Litematica");
            advance();
            mc.stop();
        }
    }
}
