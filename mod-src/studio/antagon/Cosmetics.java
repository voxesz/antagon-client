package studio.antagon;

import static studio.antagon.Reflect.*;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.WeakHashMap;

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
                                && props.getProperty(id)
                                        .matches("(badge|antagon_cape|antagon_logo_cape)(_admin)?"))
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
            return item == null ? vanilla : capeLocation(item);
        } catch (Exception ignored) {
            return vanilla;
        }
    }

    private static String capeItem(String equipped) {
        if (equipped == null) return null;
        String item = equipped.replaceAll("_admin$", "");
        return item.equals("antagon_cape") || item.equals("antagon_logo_cape") ? item : null;
    }

    private static Object capeLocation(String item) throws Exception {
        Object location = CAPE_TEXTURES.get(item);
        if (location == null) {
            location =
                    Class.forName("net.minecraft.util.ResourceLocation")
                            .getConstructor(String.class, String.class)
                            .newInstance(
                                    "antagon",
                                    item.equals("antagon_logo_cape")
                                            ? "logo-cape.png"
                                            : "cape.png");
            CAPE_TEXTURES.put(item, location);
        }
        return location;
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
            if (item != null) {
                if (!OPTIFINE_ORIGINAL.containsKey(player))
                    OPTIFINE_ORIGINAL.put(player, call(player, new String[] {"getLocationOfCape"}));
                call(player, new String[] {"setLocationOfCape"}, capeLocation(item));
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
                String equipped = EQUIPPED.get(uuid.toString().replace("-", ""));
                Class<?> text = Class.forName("net.minecraft.util.ChatComponentText");
                Object line =
                        text.getConstructor(String.class)
                                .newInstance(
                                        equipped.endsWith("_admin")
                                                ? "\u00a74[ADMIN] \u00a7r"
                                                : "");
                call(line, new String[] {"func_150257_a", "appendSibling"}, name);
                call(
                        line,
                        new String[] {"func_150257_a", "appendSibling"},
                        text.getConstructor(String.class).newInstance("   "));
                call(info, new String[] {"func_178859_a", "setDisplayName"}, line);
                TAB_ORIGINAL.put(info, original);
            }
        } catch (Exception ignored) {
        }
    }

    private static Object minecraft, tabLogo;

    /** Draws the Antagon mark just left of the ping bars for players using the client. */
    public static void tabIcon(int width, int x, int y, Object info) {
        try {
            Object profile = call(info, new String[] {"func_178845_a", "getGameProfile"});
            UUID uuid = (UUID) call(profile, new String[] {"getId"});
            if (uuid == null || !EQUIPPED.containsKey(uuid.toString().replace("-", ""))) return;
            if (minecraft == null)
                minecraft =
                        invoke(
                                Class.forName("net.minecraft.client.Minecraft"),
                                null,
                                new String[] {"func_71410_x", "getMinecraft"});
            if (tabLogo == null)
                tabLogo =
                        Class.forName("net.minecraft.util.ResourceLocation")
                                .getConstructor(String.class, String.class)
                                .newInstance("antagon", "tab-logo.png");
            Class<?> state = Class.forName("net.minecraft.client.renderer.GlStateManager");
            invoke(state, null, new String[] {"func_179147_l", "enableBlend"});
            invoke(state, null, new String[] {"func_179131_c", "color"}, 1f, 1f, 1f, 1f);
            call(
                    call(minecraft, new String[] {"func_110434_K", "getTextureManager"}),
                    new String[] {"func_110577_a", "bindTexture"},
                    tabLogo);
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
            Object profile = call(player, new String[] {"func_146103_bH", "getGameProfile"});
            UUID uuid = (UUID) call(profile, new String[] {"getId"});
            String equipped = uuid == null ? null : EQUIPPED.get(uuid.toString().replace("-", ""));
            return equipped != null && equipped.endsWith("_admin") ? "\u00a74\u00a7lADMIN" : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String tag(String item) {
        return item != null && item.endsWith("_admin")
                ? "\u00a74[ADMIN] \u00a7c[G]\u00a7r "
                : "\u00a7c[G]\u00a7r ";
    }
}
