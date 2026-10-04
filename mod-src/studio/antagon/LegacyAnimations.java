package studio.antagon;

import static studio.antagon.Reflect.*;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** First-person 1.7-style swings. Only render arguments change; no player or attack packets. */
public final class LegacyAnimations {
    public static volatile boolean enabled, block = true, bow = true, eating = true;
    public static long transforms;
    private static float useSwing = -1;
    private static long swingStart;
    private static boolean reported;

    public static void frame(float partialTick) {
        useSwing = -1;
        if (!enabled) {
            swingStart = 0;
            return;
        }
        try {
            Object mc =
                    invoke(
                            Class.forName("net.minecraft.client.Minecraft"),
                            null,
                            new String[] {"func_71410_x", "getMinecraft"});
            Object player = field(mc, "field_71439_g", "thePlayer");
            if (player == null
                    || ((Number) call(player, new String[] {"func_71052_bv", "getItemInUseCount"}))
                                    .intValue()
                            <= 0) {
                swingStart = 0;
                return;
            }
            Object stack = call(player, new String[] {"func_70694_bm", "getHeldItem"});
            if (stack == null) return;
            String action =
                    ((Enum<?>) call(stack, new String[] {"func_77975_n", "getItemUseAction"}))
                            .name();
            boolean blocking = action.equals("BLOCK");
            if (!(blocking && block
                    || action.equals("BOW") && bow
                    || (action.equals("EAT") || action.equals("DRINK")) && eating)) return;
            useSwing =
                    ((Number)
                                    call(
                                            player,
                                            new String[] {"func_70678_g", "getSwingProgress"},
                                            partialTick))
                            .floatValue();
            Object settings = field(mc, "field_71474_y", "gameSettings");
            Object attack = field(settings, "field_74312_F", "keyBindAttack");
            int key =
                    ((Number) call(attack, new String[] {"func_151463_i", "getKeyCode"}))
                            .intValue();
            boolean down =
                    key < 0
                            ? key + 100 >= 0
                                    && key + 100 < Mouse.getButtonCount()
                                    && Mouse.isButtonDown(key + 100)
                            : key > 0 && key < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(key);
            if (down && field(mc, "field_71462_r", "currentScreen") == null) {
                // 1.8 suppresses this visual swing while using an item. Animate a local render
                // cycle.
                long now = System.nanoTime();
                if (swingStart == 0) swingStart = now;
                useSwing = ((now - swingStart) % 300000000L) / 300000000f;
            } else swingStart = 0;
        } catch (Exception error) {
            useSwing = -1;
            if (!reported) {
                reported = true;
                System.err.println("[ANTAGON] 1.7 Animations unavailable: " + error);
            }
        }
    }

    public static float swing(float vanilla) {
        if (!enabled || useSwing < 0 || !Float.isFinite(useSwing)) return vanilla;
        transforms++;
        return Math.max(0, Math.min(1, useSwing));
    }

    private LegacyAnimations() {}
}
