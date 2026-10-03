package studio.antagon;

import static studio.antagon.Reflect.*;

public final class Hooks {
    public static volatile boolean hideScoreboard = false;

    public static Object cape(Object vanilla, Object player) {
        return Cosmetics.cape(vanilla, player);
    }

    public static volatile boolean itemPhysics = false;
    public static volatile float heldItemScale = 1f;
    public static long heldItemTransforms = 0;

    /** Called only before drawing a held model in first person, inside Minecraft's matrix scope. */
    public static void scaleHeldItem() {
        float scale = heldItemScale;
        if (Float.isNaN(scale) || Float.isInfinite(scale)) scale = 1f;
        scale = Math.max(.25f, Math.min(1.5f, scale));
        if (scale != 1f) {
            org.lwjgl.opengl.GL11.glScalef(scale, scale, scale);
            heldItemTransforms++;
        }
    }

    /**
     * Replaces RenderEntityItem's bob-and-spin transform: items rest on the ground with a fixed
     * yaw, flat items lie down, and stacked copies pile up. Returns how many copies to render.
     */
    public static int itemTransform(
            Object entity,
            double x,
            double y,
            double z,
            float partial,
            Object model,
            Object renderer) {
        try {
            Object stack = call(entity, new String[] {"func_92059_d", "getEntityItem"});
            if (stack == null) return 0;
            boolean block = (Boolean) call(model, new String[] {"func_177556_c", "isGui3d"});
            int copies =
                    ((Number)
                                    invoke(
                                            Class.forName(
                                                    "net.minecraft.client.renderer.entity.RenderEntityItem"),
                                            renderer,
                                            new String[] {"func_177078_a", "func_177078_a"},
                                            stack))
                            .intValue();
            float yaw =
                    ((Number) field(entity, "field_70290_d", "hoverStart")).floatValue()
                            * 57.29578f;
            Class<?> gl = Class.forName("net.minecraft.client.renderer.GlStateManager");
            invoke(
                    gl,
                    null,
                    new String[] {"func_179109_b", "translate"},
                    (float) x,
                    (float) (y + (block ? 0.125 : 0.02)),
                    (float) z);
            invoke(gl, null, new String[] {"func_179114_b", "rotate"}, yaw, 0f, 1f, 0f);
            if (!block)
                invoke(gl, null, new String[] {"func_179114_b", "rotate"}, -90f, 1f, 0f, 0f);
            invoke(gl, null, new String[] {"func_179131_c", "color"}, 1f, 1f, 1f, 1f);
            return copies;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void tabIcon(int width, int x, int y, Object info) {
        Cosmetics.tabIcon(width, x, y, info);
    }

    private Hooks() {}
}
