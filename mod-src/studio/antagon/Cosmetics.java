package studio.antagon;

import static studio.antagon.Reflect.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.imageio.ImageIO;

/** Game side of the launcher bridge. No account token or store balance enters the game. */
public final class Cosmetics {
    private static final File SNAPSHOT = new File("antagon-cosmetics.properties");
    private static final File PLAYERS = new File("antagon-players.txt");
    private static final Map<String, String> EQUIPPED = new HashMap<String, String>();
    private static final Map<String, String> NAMES = new HashMap<String, String>();
    private static final Map<Object, Object> TAB_ORIGINAL = new HashMap<Object, Object>();
    private static final Map<Object, Object> OPTIFINE_ORIGINAL = new WeakHashMap<Object, Object>();
    private static final boolean OPTIFINE = hasOptifine();
    private static long lastSnapshot = -1, lastRoster = 0;
    private static final Map<String, Object> CAPE_TEXTURES = new HashMap<String, Object>();
    private static final Map<String, Long> CAPE_RETRY = new HashMap<String, Long>();
    private static final String TOKEN = "(client|admin|(cape|hat):[a-z0-9_]{1,40})";
    private static boolean optifineLogged;

    private Cosmetics() {}

    public static void tick(Object minecraft) {
        try {
            if (SNAPSHOT.lastModified() != lastSnapshot) {
                lastSnapshot = SNAPSHOT.lastModified();
                EQUIPPED.clear();
                if (SNAPSHOT.isFile()) {
                    Properties props = new Properties();
                    try (FileInputStream in = new FileInputStream(SNAPSHOT)) {
                        props.load(in);
                    }
                    for (String id : props.stringPropertyNames())
                        if (id.matches("[0-9a-f]{32}")
                                && props.getProperty(id).matches(TOKEN + "(," + TOKEN + "){0,3}"))
                            EQUIPPED.put(id, props.getProperty(id));
                }
            }
            if (SNAPSHOT.lastModified() == 0
                    || System.currentTimeMillis() - SNAPSHOT.lastModified() > 120000)
                EQUIPPED.clear();
            syncOptifineCapes(minecraft);
            if (System.currentTimeMillis() - lastRoster < 5000) return;
            lastRoster = System.currentTimeMillis();
            Object handler = call(minecraft, new String[] {"func_147114_u", "getNetHandler"});
            if (handler == null) return;
            Collection<?> roster =
                    (Collection<?>)
                            call(handler, new String[] {"func_175106_d", "getPlayerInfoMap"});
            ArrayList<String> ids = new ArrayList<String>();
            NAMES.clear();
            for (Object info : roster) {
                Object profile = call(info, new String[] {"func_178845_a", "getGameProfile"});
                UUID uuid = (UUID) call(profile, new String[] {"getId"});
                String name = (String) call(profile, new String[] {"getName"});
                if (uuid == null || name == null) continue;
                String id = uuid.toString().replace("-", "");
                ids.add(id);
                NAMES.put(name.toLowerCase(java.util.Locale.ROOT), id);
            }
            Files.write(PLAYERS.toPath(), String.join("\n", ids).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public static Object cape(Object vanilla, Object player) {
        try {
            Object profile = call(player, new String[] {"func_146103_bH", "getGameProfile"});
            UUID uuid = (UUID) call(profile, new String[] {"getId"});
            String item =
                    uuid == null ? null : capeItem(EQUIPPED.get(uuid.toString().replace("-", "")));
            Object location = item == null ? null : capeLocation(item);
            return location == null ? vanilla : location;
        } catch (Exception ignored) {
            return vanilla;
        }
    }

    /**
     * Snapshot values are tokens such as {@code client,cape:antagon_cape,hat:antagon_crown,admin}.
     */
    private static String token(String equipped, String prefix) {
        if (equipped != null)
            for (String token : equipped.split(","))
                if (token.startsWith(prefix)) return token.substring(prefix.length());
        return null;
    }

    private static boolean admin(String equipped) {
        return equipped != null && ("," + equipped + ",").contains(",admin,");
    }

    private static String equippedOf(Object player) throws Exception {
        Object profile = call(player, new String[] {"func_146103_bH", "getGameProfile"});
        UUID uuid = (UUID) call(profile, new String[] {"getId"});
        return uuid == null ? null : EQUIPPED.get(uuid.toString().replace("-", ""));
    }

    private static String capeItem(String equipped) {
        return token(equipped, "cape:");
    }

    /** Built-in capes ship in the jar; admin-made ones are downloaded by the launcher. */
    private static Object capeLocation(String item) throws Exception {
        Object location = CAPE_TEXTURES.get(item);
        if (location != null) return location;
        if (item.startsWith("custom_")) {
            Long retry = CAPE_RETRY.get(item);
            if (retry != null && System.currentTimeMillis() < retry) return null;
            File file = new File("antagon-capes", item + ".png");
            BufferedImage image = file.isFile() ? ImageIO.read(file) : null;
            if (image == null
                    || image.getWidth() != image.getHeight() * 2
                    || image.getWidth() % 64 != 0
                    || image.getWidth() > 2048) {
                CAPE_RETRY.put(item, System.currentTimeMillis() + 5000);
                return null;
            }
            Object texture =
                    Class.forName("net.minecraft.client.renderer.texture.DynamicTexture")
                            .getConstructor(BufferedImage.class)
                            .newInstance(image);
            location =
                    call(
                            call(minecraft(), new String[] {"func_110434_K", "getTextureManager"}),
                            new String[] {"func_110578_a", "getDynamicTextureLocation"},
                            "antagon_" + item,
                            texture);
        } else location = resource(item.equals("antagon_logo_cape") ? "logo-cape.png" : "cape.png");
        CAPE_TEXTURES.put(item, location);
        return location;
    }

    private static Object resource(String name) throws Exception {
        return Class.forName("net.minecraft.util.ResourceLocation")
                .getConstructor(String.class, String.class)
                .newInstance("antagon", name);
    }

    private static Object minecraft() throws Exception {
        if (minecraft == null)
            minecraft =
                    invoke(
                            Class.forName("net.minecraft.client.Minecraft"),
                            null,
                            new String[] {"func_71410_x", "getMinecraft"});
        return minecraft;
    }

    private static boolean hasOptifine() {
        try {
            Class.forName("net.optifine.player.CapeUtils");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** OptiFine renders its own cape location, which can bypass the vanilla getter. */
    private static void syncOptifineCapes(Object minecraft) {
        if (!OPTIFINE) return;
        try {
            Object world = field(minecraft, "field_71441_e", "theWorld");
            if (world == null) return;
            Collection<?> players = (Collection<?>) field(world, "field_73010_i", "playerEntities");
            for (Object player : players) beforeRender(player);
        } catch (Exception ignored) {
        }
    }

    /** Called again immediately before each player render, after OptiFine updates. */
    public static void beforeRender(Object player) {
        if (!OPTIFINE || player == null) return;
        try {
            Object profile = call(player, new String[] {"func_146103_bH", "getGameProfile"});
            UUID uuid = (UUID) call(profile, new String[] {"getId"});
            String item =
                    uuid == null ? null : capeItem(EQUIPPED.get(uuid.toString().replace("-", "")));
            Object location = item == null ? null : capeLocation(item);
            if (location != null) {
                if (!OPTIFINE_ORIGINAL.containsKey(player))
                    OPTIFINE_ORIGINAL.put(player, call(player, new String[] {"getLocationOfCape"}));
                call(player, new String[] {"setLocationOfCape"}, location);
                if (!optifineLogged) {
                    System.out.println("[ANTAGON] OptiFine cape bridge active");
                    optifineLogged = true;
                }
            } else if (OPTIFINE_ORIGINAL.containsKey(player)) {
                call(player, new String[] {"setLocationOfCape"}, OPTIFINE_ORIGINAL.remove(player));
            }
        } catch (Exception ignored) {
        }
    }

    public static void chat(Object event) {
        try {
            Object message = field(event, "message");
            if (message == null || ((Number) field(event, "type")).intValue() == 2) return;
            String plain =
                    (String) call(message, new String[] {"func_150260_c", "getUnformattedText"});
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(
                                    "^(?:<([A-Za-z0-9_]{1,16})>|(?:\\[[A-Za-z0-9_ +.\\-]{1,32}\\]"
                                            + " ?)?([A-Za-z0-9_]{1,16}):)")
                            .matcher(plain);
            if (!m.find()) return;
            String name = m.group(1) == null ? m.group(2) : m.group(1);
            String uuid = NAMES.get(name.toLowerCase(java.util.Locale.ROOT));
            if (uuid == null || !EQUIPPED.containsKey(uuid)) return;
            Object prefix =
                    Class.forName("net.minecraft.util.ChatComponentText")
                            .getConstructor(String.class)
                            .newInstance(tag(EQUIPPED.get(uuid)));
            call(prefix, new String[] {"func_150257_a", "appendSibling"}, message);
            setField(event, prefix, "message");
        } catch (Exception ignored) {
        }
    }

    public static void tab(Object minecraft, boolean before) {
        try {
            if (!before) {
                for (Map.Entry<Object, Object> entry : TAB_ORIGINAL.entrySet())
                    call(
                            entry.getKey(),
                            new String[] {"func_178859_a", "setDisplayName"},
                            entry.getValue());
                TAB_ORIGINAL.clear();
                return;
            }
            Object handler = call(minecraft, new String[] {"func_147114_u", "getNetHandler"});
            if (handler == null) return;
            Collection<?> roster =
                    (Collection<?>)
                            call(handler, new String[] {"func_175106_d", "getPlayerInfoMap"});
            for (Object info : roster) {
                Object profile = call(info, new String[] {"func_178845_a", "getGameProfile"});
                UUID uuid = (UUID) call(profile, new String[] {"getId"});
                if (uuid == null || !EQUIPPED.containsKey(uuid.toString().replace("-", "")))
                    continue;
                Object original = call(info, new String[] {"func_178854_k", "getDisplayName"});
                Object name =
                        original == null
                                ? Class.forName("net.minecraft.util.ChatComponentText")
                                        .getConstructor(String.class)
                                        .newInstance(
                                                (String) call(profile, new String[] {"getName"}))
                                : original;
                Object line =
                        Class.forName("net.minecraft.util.ChatComponentText")
                                .getConstructor(String.class)
                                .newInstance("");
                call(line, new String[] {"func_150257_a", "appendSibling"}, name);
                call(
                        line,
                        new String[] {"func_150257_a", "appendSibling"},
                        Class.forName("net.minecraft.util.ChatComponentText")
                                .getConstructor(String.class)
                                .newInstance("   "));
                call(info, new String[] {"func_178859_a", "setDisplayName"}, line);
                TAB_ORIGINAL.put(info, original);
            }
        } catch (Exception ignored) {
        }
    }

    private static Object minecraft, tabLogo, tabLogoAdmin;

    /** Draws the Antagon mark just left of the ping bars: red for client users, gold for admins. */
    public static void tabIcon(int width, int x, int y, Object info) {
        try {
            Object profile = call(info, new String[] {"func_178845_a", "getGameProfile"});
            UUID uuid = (UUID) call(profile, new String[] {"getId"});
            String equipped = uuid == null ? null : EQUIPPED.get(uuid.toString().replace("-", ""));
            if (equipped == null) return;
            if (tabLogo == null) {
                tabLogo = resource("tab-logo.png");
                tabLogoAdmin = resource("tab-logo-admin.png");
            }
            Class<?> state = Class.forName("net.minecraft.client.renderer.GlStateManager");
            invoke(state, null, new String[] {"func_179147_l", "enableBlend"});
            invoke(state, null, new String[] {"func_179131_c", "color"}, 1f, 1f, 1f, 1f);
            call(
                    call(minecraft(), new String[] {"func_110434_K", "getTextureManager"}),
                    new String[] {"func_110577_a", "bindTexture"},
                    admin(equipped) ? tabLogoAdmin : tabLogo);
            invoke(
                    Class.forName("net.minecraft.client.gui.Gui"),
                    null,
                    new String[] {"func_146110_a", "drawModalRectWithCustomSizedTexture"},
                    x + width - 21,
                    y,
                    0f,
                    0f,
                    8,
                    8,
                    8f,
                    8f);
        } catch (Exception ignored) {
        }
    }

    /** Text shown above a player's nametag; the place for future tags. */
    public static String tagFor(Object player) {
        try {
            return admin(equippedOf(player)) ? "§4§lADMIN" : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String tag(String item) {
        return admin(item) ? "§4[ADMIN] §c[G]§r " : "§c[G]§r ";
    }

    private static boolean layersInstalled;
    private static Object crownGold, crownGems, crownTexture;

    /** Adds the hat layer to both player renderers (default and slim arms). */
    public static void installLayers(Object minecraft) {
        if (layersInstalled) return;
        try {
            Object manager = call(minecraft, new String[] {"func_175598_ae", "getRenderManager"});
            Map<?, ?> skins = (Map<?, ?>) field(manager, "field_178636_l", "skinMap");
            if (skins == null || skins.isEmpty()) return;
            Class<?> layerType =
                    Class.forName("net.minecraft.client.renderer.entity.layers.LayerRenderer");
            for (final Object renderer : skins.values()) {
                Object layer =
                        Proxy.newProxyInstance(
                                layerType.getClassLoader(),
                                new Class<?>[] {layerType},
                                (proxy, method, args) -> {
                                    String name = method.getName();
                                    if (name.equals("func_177141_a")
                                            || name.equals("doRenderLayer")) {
                                        renderHat(renderer, args[0], (Float) args[7]);
                                        return null;
                                    }
                                    if (name.equals("hashCode"))
                                        return System.identityHashCode(proxy);
                                    if (name.equals("equals")) return proxy == args[0];
                                    if (name.equals("toString")) return "AntagonHatLayer";
                                    return false;
                                });
                @SuppressWarnings("unchecked")
                List<Object> layers =
                        (List<Object>) field(renderer, "field_177097_h", "layerRenderers");
                layers.add(layer);
            }
            System.out.println("[ANTAGON] Hat layer installed");
        } catch (Exception e) {
            System.out.println("[ANTAGON] Hat layer unavailable: " + e);
        }
        layersInstalled = true;
    }

    /** Gold band with spikes around the top of the head, in head-model units before scaling. */
    private static final float[][] CROWN_GOLD = {
        {-4, -8.25f, -4, 8, 2, 1},
        {-4, -8.25f, 3, 8, 2, 1},
        {-4, -8.25f, -3, 1, 2, 6},
        {3, -8.25f, -3, 1, 2, 6},
        {-4, -10.25f, -4, 1, 2, 1},
        {3, -10.25f, -4, 1, 2, 1},
        {-4, -10.25f, 3, 1, 2, 1},
        {3, -10.25f, 3, 1, 2, 1},
        {-0.5f, -11.25f, -4, 1, 3, 1},
        {-0.5f, -10.25f, 3, 1, 2, 1},
        {-4, -10.25f, -0.5f, 1, 2, 1},
        {3, -10.25f, -0.5f, 1, 2, 1},
    };

    private static final float[][] CROWN_GEMS = {
        {-0.5f, -7.75f, -4.4f, 1, 1, 1},
        {-0.5f, -7.75f, 3.4f, 1, 1, 1},
        {-4.4f, -7.75f, -0.5f, 1, 1, 1},
        {3.4f, -7.75f, -0.5f, 1, 1, 1},
    };

    private static Object boxes(Object owner, int u, int v, float[][] boxes) throws Exception {
        Object model =
                Class.forName("net.minecraft.client.model.ModelRenderer")
                        .getConstructor(
                                Class.forName("net.minecraft.client.model.ModelBase"),
                                int.class,
                                int.class)
                        .newInstance(owner, u, v);
        for (float[] b : boxes)
            call(
                    model,
                    new String[] {"func_78789_a", "addBox"},
                    b[0],
                    b[1],
                    b[2],
                    (int) b[3],
                    (int) b[4],
                    (int) b[5]);
        return model;
    }

    static void renderHat(Object renderer, Object player, float scale) {
        try {
            if (!"antagon_crown".equals(token(equippedOf(player), "hat:"))) return;
            if ((Boolean) call(player, new String[] {"func_82150_aj", "isInvisible"})
                    || call(player, new String[] {"func_82169_q", "getCurrentArmor"}, 3) != null)
                return;
            if (crownGold == null) {
                Object owner = Class.forName("net.minecraft.client.model.ModelBiped").newInstance();
                crownGold = boxes(owner, 0, 0, CROWN_GOLD);
                crownGems = boxes(owner, 0, 16, CROWN_GEMS);
                crownTexture = resource("crown.png");
            }
            Class<?> gl = Class.forName("net.minecraft.client.renderer.GlStateManager");
            invoke(gl, null, new String[] {"func_179094_E", "pushMatrix"});
            try {
                if ((Boolean) call(player, new String[] {"func_70093_af", "isSneaking"}))
                    invoke(gl, null, new String[] {"func_179109_b", "translate"}, 0f, 0.2f, 0f);
                Object head =
                        field(
                                call(renderer, new String[] {"func_177087_b", "getMainModel"}),
                                "field_78116_c",
                                "bipedHead");
                call(head, new String[] {"func_78794_c", "postRender"}, scale);
                invoke(gl, null, new String[] {"func_179152_a", "scale"}, 1.15f, 1.15f, 1.15f);
                invoke(gl, null, new String[] {"func_179131_c", "color"}, 1f, 1f, 1f, 1f);
                call(
                        call(minecraft(), new String[] {"func_110434_K", "getTextureManager"}),
                        new String[] {"func_110577_a", "bindTexture"},
                        crownTexture);
                call(crownGold, new String[] {"func_78785_a", "render"}, scale);
                call(crownGems, new String[] {"func_78785_a", "render"}, scale);
            } finally {
                invoke(gl, null, new String[] {"func_179121_F", "popMatrix"});
            }
        } catch (Exception ignored) {
        }
    }
}
