package studio.antagon;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;

// Built only into the isolated test profile; never distributed with the client.
public final class ModernSmoke implements ClientModInitializer {
    private int phase;
    private long changed;
    private long diagnostic;

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
    }

    private void screenshot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
            message -> System.out.println("[MODERN TEST] Screenshot " + name + " " + message.getString()));
    }

    private void tick(Minecraft mc) {
        if (!mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
        long elapsed = System.currentTimeMillis() - changed;
        if (System.currentTimeMillis() - diagnostic > 10000) {
            diagnostic = System.currentTimeMillis();
            System.out.println("[MODERN TEST] Phase=" + phase + "; screen=" + mc.gui.screen());
        }
        if (phase == 0) {
            for (String id : new String[]{"iris", "sodium", "fabric-api", "lithium", "ferritecore", "essential"}) {
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
            advance();
        } else if (phase == 5 && elapsed > 5000) {
            System.out.println("[MODERN TEST] OK: menu, Iris screen, world and active shaders");
            advance();
            mc.stop();
        }
    }
}
