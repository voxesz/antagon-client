package studio.antagon;

import static studio.antagon.ModuleRegistry.*;
import static studio.antagon.Reflect.*;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

import javax.imageio.ImageIO;

@Mod(
        modid = "antagonhud",
        name = "Antagon Client",
        version = "0.1.0",
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.9]",
        acceptableRemoteVersions = "*")
public class AntagonHud {
    private static final int RED = 0xFFEE1515, WHITE = 0xFFF0EEE8, GRAY = 0xFF8A8883;
    private static final int FRIENDS_BUTTON_ID = 0xA71C,
            STORE_BUTTON_ID = 0xA71D,
            ADMIN_BUTTON_ID = 0xA71E,
            RADIO_BUTTON_ID = 0xA71F;
    private static final File UI_REQUEST = new File("antagon-ui-request.txt");
    private static final File SESSION = new File("antagon-session.properties");
    private Object mc;
    private Class<?> minecraft;
    private final LinkedList<Long> left = new LinkedList<Long>(), right = new LinkedList<Long>();
    private final Properties config = new Properties();
    private final File configFile = new File("config/antagon-hud.properties");
    private long lastLoad = 0, modified = -1;
    private boolean hidden = false, f8 = false, reported = false;
    private final PixelFont font = new PixelFont();
    private final ClientMenus clientMenus = new ClientMenus();
    private long lastAutoText = 0;
    private int smokeTicks = 0, worldTicks = 0;
    private final SimpleDateFormat clock12 = new SimpleDateFormat("hh:mm a");
    private final SimpleDateFormat clock24 = new SimpleDateFormat("HH:mm");
    private String clockText = "", clockFormat = "";
    private long clockMinute = -1;
    private boolean smokeStarted = false;
    private double smokeRadioPosition;
    private String smokeRadioTrack;
    private final Map<String, float[]> bounds = new HashMap<String, float[]>();
    private float hudScale = 1, hudWidth, hudHeight;
    private Menu menu;
    private boolean sprintToggled = false, sprintWas = false;
    private volatile boolean smokeConfig = false;
    private boolean looking = false, lookToggled = false, lookKeyWas = false, smokeLook = false;
    private float camYaw, camPitch, savedYaw, savedPitch, savedPrevYaw, savedPrevPitch;
    private int savedView;

    private volatile String radioState = "off", radioTitle = "", radioArtist = "", radioArt = "";
    private volatile float radioPos = 0, radioDur = 0;
    private volatile int radioVolume = -1;
    private volatile long volumeSentAt = 0;
    private double lastReach = 0;
    private int combo = 0, lastHurt = 0, savedHurt = 0;
    private long comboAt = 0, pendingAt = 0;
    private Object pendingTarget;
    private long lastReachAt = 0;
    private volatile long radioStamp = 0;
    private volatile BufferedImage artPending;
    private int artTexture = 0;
    private boolean menuLogoLogged = false;
    private boolean capeRenderLogged = false;
    private String lastChat = null;
    private int chatId = 0x5A0000, chatCount = 0;
    private static final String YAW = "field_70177_z",
            PITCH = "field_70125_A",
            PREV_YAW = "field_70126_B",
            PREV_PITCH = "field_70127_C";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        minecraft = Class.forName("net.minecraft.client.Minecraft");
        mc = invoke(minecraft, null, new String[] {"func_71410_x", "getMinecraft"});
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        load();
        Thread radio = new Thread(this::radioLoop, "Antagon Radio");
        radio.setDaemon(true);
        radio.start();
        System.out.println(
                "[ANTAGON] HUD ready — 0.1.0 / FPS CPS Keystrokes Coordinates Ping Clock NoHurtCam"
                        + " / Antagon mods");
    }

    private void load() {
        if (configFile.lastModified() == modified) return;
        try (InputStream in = new FileInputStream(configFile)) {
            Properties next = new Properties();
            next.load(in);
            config.clear();
            config.putAll(next);
            modified = configFile.lastModified();
        } catch (IOException ignored) {
        }
    }

    private void save() {
        if (smokeConfig) return;
        try {
            configFile.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(configFile)) {
                config.store(out, "Antagon HUD");
            }
            modified = configFile.lastModified();
        } catch (IOException e) {
            System.err.println("[ANTAGON] Could not save config: " + e);
        }
    }

    private boolean on(String key, boolean fallback) {
        return Boolean.parseBoolean(config.getProperty(key, "" + fallback));
    }

    private boolean enabled(String mod) {
        return on(mod, defaultEnabled(mod));
    }

    private float number(String key, float fallback) {
        try {
            return Float.parseFloat(config.getProperty(key, "" + fallback));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String opt(String mod, String key) {
        String property = mod + "." + key;
        return config.getProperty(property, defaultValue(property));
    }

    private float percent(String mod, String key) {
        try {
            return Float.parseFloat(opt(mod, key).replace("%", "")) / 100f;
        } catch (Exception e) {
            return 1;
        }
    }

    private boolean flag(String mod, String key) {
        return opt(mod, key).equals("on");
    }

    private int keyOf(String mod, String key) {
        return codeOf(opt(mod, key));
    }

    private static int codeOf(String v) {
        if (v.equals("ALT")) return Keyboard.KEY_LMENU;
        if (v.matches("MOUSE\\d{1,2}")) return Integer.parseInt(v.substring(5)) - 101;
        int code = Keyboard.getKeyIndex(v);
        return code == Keyboard.KEY_NONE ? -1 : code;
    }

    private static int colorOf(String name) {
        for (int i = 0; i < COLOR_NAMES.length; i++)
            if (COLOR_NAMES[i].equals(name)) return COLORS[i];
        return COLORS[0];
    }

    private void prune(LinkedList<Long> clicks) {
        long cutoff = System.currentTimeMillis() - 1000;
        while (!clicks.isEmpty() && clicks.getFirst() < cutoff) clicks.removeFirst();
    }

    private static boolean isDown(int code) {
        return code < -1 ? Mouse.isButtonDown(code + 100) : code > 0 && Keyboard.isKeyDown(code);
    }

    private void fullBright() {
        try {
            Object settings = field(mc, "field_71474_y", "gameSettings");
            boolean on = enabled("fullbright");
            if (on && config.getProperty("fullbright.gamma") == null) {
                config.setProperty("fullbright.gamma", "" + getF(settings, "field_74333_Y"));
                config.setProperty(
                        "fullbright.entityShadows", "" + field(settings, "field_181151_V"));
                save();
            }
            if (on) {
                setF(settings, "field_74333_Y", 100f);
                setField(
                        settings,
                        !flag("fullbright", "shadows")
                                && Boolean.parseBoolean(
                                        config.getProperty("fullbright.entityShadows", "true")),
                        "field_181151_V");
            } else if (config.getProperty("fullbright.gamma") != null) {
                setF(
                        settings,
                        "field_74333_Y",
                        Float.parseFloat(config.getProperty("fullbright.gamma")));
                setField(
                        settings,
                        Boolean.parseBoolean(
                                config.getProperty("fullbright.entityShadows", "true")),
                        "field_181151_V");
                config.remove("fullbright.gamma");
                config.remove("fullbright.entityShadows");
                save();
            }
        } catch (Exception e) {
            report(e);
        }
    }

    private int statusTicks = 0;
    private String lastStatus = "";

    private void writeStatus() {
        if (++statusTicks % 40 != 0) return;
        try {
            String status = "menu";
            if (field(mc, "field_71441_e", "theWorld") != null) {
                Object server = call(mc, new String[] {"func_147104_D", "getCurrentServerData"});
                if ((Boolean) call(mc, new String[] {"func_71356_B", "isSingleplayer"}))
                    status = "singleplayer";
                else
                    status =
                            server == null
                                    ? "playing"
                                    : "server " + field(server, "field_78845_b", "serverIP");
            }
            if (status.equals(lastStatus)) return;
            lastStatus = status;
            java.nio.file.Files.write(
                    new File("antagon-status.txt").toPath(), status.getBytes("UTF-8"));
        } catch (Exception e) {
            report(e);
        }
    }

    private void boundKey(int code) {
        if (enabled("autotext"))
            for (int i = 1; i <= AUTOTEXT_SLOTS; i++) {
                String text = config.getProperty("autotext." + i + ".text", "").trim();
                if (!text.isEmpty()
                        && code == codeOf(config.getProperty("autotext." + i + ".key", "NENHUMA"))
                        && System.currentTimeMillis() - lastAutoText > 500) {
                    lastAutoText = System.currentTimeMillis();
                    try {
                        call(
                                field(mc, "field_71439_g", "thePlayer"),
                                new String[] {"func_71165_d", "sendChatMessage"},
                                text);
                    } catch (Exception e) {
                        report(e);
                    }
                }
            }
        if (!enabled("radio")) return;
        if (code == keyOf("radio", "prev")) Radio.command("previous");
        else if (code == keyOf("radio", "pause")) radioToggle();
        else if (code == keyOf("radio", "next")) Radio.command("next");
    }

    @SubscribeEvent
    public void mouse(MouseEvent event) {
        if (!event.buttonstate) return;
        if (event.button >= 0) boundKey(event.button - 100);
        if (event.button == 0) left.add(System.currentTimeMillis());
        if (event.button == 1) right.add(System.currentTimeMillis());
    }

    @SubscribeEvent
    public void key(InputEvent.KeyInputEvent event) {
        if (!Keyboard.getEventKeyState()) return;
        int k = Keyboard.getEventKey();
        if (k == Keyboard.KEY_RSHIFT) {
            openMenu();
            return;
        }
        boundKey(k);
    }

    private void openMenu() {
        openMenu(null);
    }

    private void openMenu(GuiScreen parent) {
        try {
            call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, new Menu(parent));
        } catch (Exception e) {
            System.err.println("[ANTAGON] Menu error: " + e);
        }
    }

    @SubscribeEvent
    public void menuButtons(GuiScreenEvent.InitGuiEvent.Post event) {
        try {
            Object gui = field(event, "gui");
            if (!ClientMenus.supports(gui)) return;
            boolean paused = gui.getClass().getName().endsWith("GuiIngameMenu");
            @SuppressWarnings("unchecked")
            List<Object> buttons = (List<Object>) field(event, "buttonList");
            for (Object button : buttons)
                if (((Number) field(button, "field_146127_k", "id")).intValue()
                        == FRIENDS_BUTTON_ID) return;
            if (!paused) {
                for (Iterator<Object> iterator = buttons.iterator(); iterator.hasNext(); )
                    if (((Number) field(iterator.next(), "field_146127_k", "id")).intValue() == 14)
                        iterator.remove();
            }
            buttons.add(newButton(FRIENDS_BUTTON_ID, 0, 0, 20, ""));
            buttons.add(newButton(STORE_BUTTON_ID, 0, 0, 20, ""));
            buttons.add(newButton(RADIO_BUTTON_ID, 0, 0, 20, ""));
            if (sessionAdmin()) buttons.add(newButton(ADMIN_BUTTON_ID, 0, 0, 20, ""));
            clientMenus.layout(
                    gui,
                    buttons,
                    paused,
                    FRIENDS_BUTTON_ID,
                    STORE_BUTTON_ID,
                    ADMIN_BUTTON_ID,
                    RADIO_BUTTON_ID);
        } catch (Exception e) {
            report(e);
        }
    }

    private Object newButton(int id, int x, int y, int width, String label) throws Exception {
        return Class.forName("net.minecraft.client.gui.GuiButton")
                .getConstructor(int.class, int.class, int.class, int.class, int.class, String.class)
                .newInstance(id, x, y, width, 20, label);
    }

    private boolean sessionAdmin() {
        try (FileInputStream input = new FileInputStream(SESSION)) {
            Properties role = new Properties();
            role.load(input);
            return "true".equals(role.getProperty("admin"));
        } catch (IOException ignored) {
            return false;
        }
    }

    @SubscribeEvent
    public void menuButtonLogo(GuiScreenEvent.DrawScreenEvent.Pre event) {
        try {
            Object gui = field(event, "gui");
            if (!ClientMenus.supports(gui)) return;
            boolean paused = gui.getClass().getName().endsWith("GuiIngameMenu");
            @SuppressWarnings("unchecked")
            List<Object> buttons = (List<Object>) field(gui, "field_146292_n", "buttonList");
            clientMenus.draw(
                    gui,
                    buttons,
                    ((Number) field(event, "mouseX")).intValue(),
                    ((Number) field(event, "mouseY")).intValue(),
                    paused,
                    FRIENDS_BUTTON_ID,
                    STORE_BUTTON_ID,
                    ADMIN_BUTTON_ID,
                    RADIO_BUTTON_ID);
            event.setCanceled(true);
            if (!menuLogoLogged) {
                System.out.println("[ANTAGON] Menu icons rendered");
                menuLogoLogged = true;
            }
        } catch (Exception e) {
            report(e);
        }
    }

    @SubscribeEvent
    public void menuButtonClick(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        try {
            Object button = field(event, "button");
            int id = ((Number) field(button, "field_146127_k", "id")).intValue();
            GuiScreen screen = (GuiScreen) field(event, "gui");
            if (id == RADIO_BUTTON_ID)
                call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, new RadioMenu(screen));
            else if (id == FRIENDS_BUTTON_ID || id == STORE_BUTTON_ID)
                call(
                        mc,
                        new String[] {"func_147108_a", "displayGuiScreen"},
                        new Hub(screen, id == FRIENDS_BUTTON_ID ? "friends" : "store"));
            else if (id == ADMIN_BUTTON_ID && sessionAdmin()) requestLauncherView("admin");
            else return;
            event.setCanceled(true);
        } catch (Exception e) {
            report(e);
        }
    }

    private void requestLauncherView(String view) throws IOException {
        File temporary = new File("antagon-ui-request.tmp");
        Files.write(temporary.toPath(), view.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(
                    temporary.toPath(),
                    UI_REQUEST.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporary.toPath(), UI_REQUEST.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SubscribeEvent
    public void renderPlayer(RenderPlayerEvent.Pre event) {
        try {
            Cosmetics.beforeRender(field(event, "entityPlayer"));
            if (!capeRenderLogged) {
                System.out.println("[ANTAGON] Player render cape bridge active");
                capeRenderLogged = true;
            }
        } catch (Exception ignored) {
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START && enabled("hitdelay"))
            try {
                setField(mc, 0, "field_71429_W", "leftClickCounter");
            } catch (Exception e) {
                report(e);
            }
        if (event.phase != TickEvent.Phase.END) return;
        Cosmetics.tick(mc);
        Cosmetics.installLayers(mc);
        boolean pressed = Keyboard.isKeyDown(Keyboard.KEY_F8);
        if (pressed && !f8) hidden = !hidden;
        f8 = pressed;
        if (System.currentTimeMillis() - lastLoad > 1000) {
            load();
            lastLoad = System.currentTimeMillis();
        }
        prune(left);
        prune(right);
        sprint();
        chatSettings();
        Hooks.hideScoreboard = enabled("scoreboard");
        Hooks.itemPhysics = enabled("itemphysics");
        Hooks.heldItemScale = enabled("itemsize") ? percent("itemsize", "scale") : 1f;
        LegacyAnimations.enabled = enabled("oldanimations");
        LegacyAnimations.block = flag("oldanimations", "block");
        LegacyAnimations.bow = flag("oldanimations", "bow");
        LegacyAnimations.eating = flag("oldanimations", "eating");
        fullBright();
        if (enabled("hitdelay"))
            try {
                setField(mc, 0, "field_71429_W", "leftClickCounter");
            } catch (Exception e) {
                report(e);
            }
        writeStatus();
        comboTick();
        hitColor();
        if (Boolean.getBoolean("antagon.smoke")) smoke();
        if (System.getProperty("antagon.wallpaper") != null) wallpaper();
    }

    private int wpTicks = 0;
    private boolean wpStarted = false, wpDepth = false;

    private void wallpaper() {
        try {
            String[] a = System.getProperty("antagon.wallpaper").split(",");
            long seed = Long.parseLong(a[0]);
            double x = Double.parseDouble(a[1]),
                    y = Double.parseDouble(a[2]),
                    z = Double.parseDouble(a[3]);
            float yaw = Float.parseFloat(a[4]), pitch = Float.parseFloat(a[5]);
            long time = Long.parseLong(a[6]);
            if (!wpStarted) {
                if (++wpTicks < 80) return;
                wpStarted = true;
                wpTicks = 0;
                Class<?> ws = Class.forName("net.minecraft.world.WorldSettings"),
                        mode = Class.forName("net.minecraft.world.WorldSettings$GameType"),
                        wt = Class.forName("net.minecraft.world.WorldType");
                Field normal = wt.getDeclaredField("field_77137_b");
                normal.setAccessible(true);
                Object options =
                        ws.getConstructor(long.class, mode, boolean.class, boolean.class, wt)
                                .newInstance(
                                        seed,
                                        Enum.valueOf((Class) mode, "CREATIVE"),
                                        true,
                                        false,
                                        normal.get(null));
                call(
                        mc,
                        new String[] {"func_71371_a", "launchIntegratedServer"},
                        "Antagon-Wallpaper-" + seed,
                        "Antagon Wallpaper",
                        options);
                return;
            }
            Object player = field(mc, "field_71439_g", "thePlayer");
            if (player == null) return;
            if (field(mc, "field_71462_r", "currentScreen") != null)
                call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, (Object) null);
            Object settings = field(mc, "field_71474_y", "gameSettings");
            setField(settings, true, "field_74319_N", "hideGUI");
            call(
                    field(mc, "field_71458_u", "guiAchievement"),
                    new String[] {"func_146257_b", "clearAchievements"});
            setField(
                    field(player, "field_71075_bZ", "capabilities"),
                    true,
                    "field_75100_b",
                    "isFlying");
            call(
                    player,
                    new String[] {"func_70012_b", "setLocationAndAngles"},
                    x,
                    y,
                    z,
                    yaw,
                    pitch);
            setF(player, PREV_YAW, yaw);
            setF(player, PREV_PITCH, pitch);
            Object server =
                    call(
                            call(mc, new String[] {"func_71401_C", "getIntegratedServer"}),
                            new String[] {"func_71218_a", "worldServerForDimension"},
                            0);
            call(server, new String[] {"func_72877_b", "setWorldTime"}, time);
            Object info = call(server, new String[] {"func_72912_H", "getWorldInfo"});
            call(info, new String[] {"func_76084_b", "setRaining"}, false);
            call(info, new String[] {"func_76069_a", "setThundering"}, false);
            call(
                    field(mc, "field_71441_e", "theWorld"),
                    new String[] {"func_72877_b", "setWorldTime"},
                    time);
            wpTicks++;
            if (wpTicks == 700) wpDepth = true;
            if (wpTicks == 702) shot("wallpaper.png");
            if (wpTicks == 720) call(mc, new String[] {"func_71400_g", "shutdown"});
        } catch (Exception e) {
            System.err.println("[ANTAGON WALLPAPER] " + e);
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void wallpaperDepth(RenderWorldLastEvent event) {
        if (!wpDepth) return;
        wpDepth = false;
        try {
            int w = ((Number) field(mc, "field_71443_c", "displayWidth")).intValue(),
                    h = ((Number) field(mc, "field_71440_d", "displayHeight")).intValue();
            java.nio.FloatBuffer buf = BufferUtils.createFloatBuffer(w * h);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
            float near = .05f, far = 12 * 16 * 1.4142f;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            for (int yy = 0; yy < h; yy++)
                for (int xx = 0; xx < w; xx++) {
                    float d = buf.get(yy * w + xx),
                            linear = 2 * near * far / (far + near - (2 * d - 1) * (far - near));
                    int v =
                            d >= .99999f
                                    ? 0
                                    : (int)
                                            Math.max(
                                                    0,
                                                    Math.min(
                                                            255,
                                                            255 * (1 - Math.sqrt(linear / far))));
                    img.getRaster().setSample(xx, h - 1 - yy, 0, v);
                }
            ImageIO.write(img, "png", new File("screenshots/wallpaper-depth.png"));
            System.out.println("[ANTAGON WALLPAPER] depth saved");
        } catch (Exception e) {
            System.err.println("[ANTAGON WALLPAPER] " + e);
        }
    }

    private void chatSettings() {
        if (!enabled("chat")) return;
        try {
            Object settings = field(mc, "field_71474_y", "gameSettings");
            float scale = percent("chat", "scale"),
                    width = percent("chat", "width"),
                    lines = (Integer.parseInt(opt("chat", "lines")) * 9 - 20) / 160f;
            float[] now = {
                ((Number) field(settings, "field_96691_E")).floatValue(),
                ((Number) field(settings, "field_96692_F")).floatValue(),
                ((Number) field(settings, "field_96693_G")).floatValue()
            };
            if (Math.abs(now[0] - scale) + Math.abs(now[1] - width) + Math.abs(now[2] - lines)
                    < .001f) return;
            setField(settings, scale, "field_96691_E");
            setField(settings, width, "field_96692_F");
            setField(settings, lines, "field_96693_G");
            call(
                    call(
                            field(mc, "field_71456_v", "ingameGUI"),
                            new String[] {"func_146158_b", "getChatGUI"}),
                    new String[] {"func_146245_b", "refreshChat"});
        } catch (Exception e) {
            report(e);
        }
    }

    @SubscribeEvent
    public void chat(ClientChatReceivedEvent event) {
        Cosmetics.chat(event);
        if (!enabled("chat")) return;
        try {
            Object message = field(event, "message");
            if (message == null || ((Number) field(event, "type")).intValue() == 2) return;
            boolean stack = flag("chat", "stack"), time = flag("chat", "time");
            if (!stack && !time) return;
            String text =
                    (String) call(message, new String[] {"func_150254_d", "getFormattedText"});
            if (stack && text.equals(lastChat)) chatCount++;
            else {
                lastChat = text;
                chatCount = 1;
                chatId++;
            }
            Constructor<?> component =
                    Class.forName("net.minecraft.util.ChatComponentText")
                            .getConstructor(String.class);
            Object out =
                    component.newInstance(
                            time
                                    ? "\u00a77["
                                            + new SimpleDateFormat("HH:mm").format(new Date())
                                            + "] \u00a7r"
                                    : "");
            call(out, new String[] {"func_150257_a", "appendSibling"}, message);
            if (chatCount > 1)
                call(
                        out,
                        new String[] {"func_150257_a", "appendSibling"},
                        component.newInstance(" \u00a77(x" + chatCount + ")"));
            Object chatGui =
                    call(
                            field(mc, "field_71456_v", "ingameGUI"),
                            new String[] {"func_146158_b", "getChatGUI"});
            call(
                    chatGui,
                    new String[] {"func_146234_a", "printChatMessageWithOptionalDeletion"},
                    out,
                    stack ? chatId : 0);
            event.setCanceled(true);
        } catch (Exception e) {
            report(e);
        }
    }

    @SubscribeEvent
    public void cosmeticsTabBefore(RenderGameOverlayEvent.Pre event) {
        if (event.type == RenderGameOverlayEvent.ElementType.PLAYER_LIST) Cosmetics.tab(mc, true);
    }

    @SubscribeEvent
    public void cosmeticsTabAfter(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.PLAYER_LIST) Cosmetics.tab(mc, false);
    }

    private void radioPlay() {
        try {
            call(
                    mc,
                    new String[] {"func_147108_a", "displayGuiScreen"},
                    new RadioMenu((GuiScreen) field(mc, "field_71462_r", "currentScreen")));
        } catch (Exception e) {
            report(e);
        }
    }

    private void radioToggle() {
        Radio.command("toggle");
    }

    private void radioLoop() {
        while (true) {
            try {
                if (!smokeConfig || NativeRadio.collection != null) {
                    Properties p = Radio.status();
                    radioState = p.getProperty("state", "off");
                    radioTitle = p.getProperty("title", "");
                    String collection = p.getProperty("collection", "");
                    radioArtist = p.getProperty("artist", "");
                    if (p.getProperty("mode", "").equals("radio"))
                        radioArtist = "AO VIVO · " + radioArtist;
                    if (radioTitle.isEmpty()) radioArtist = collection;
                    radioPos = Platform.number(p.getProperty("position", "0"));
                    radioDur = Platform.number(p.getProperty("duration", "0"));
                    radioStamp = System.currentTimeMillis();
                    if (System.currentTimeMillis() - volumeSentAt > 1500)
                        radioVolume = (int) Platform.number(p.getProperty("volume", "65"));
                    String art = p.getProperty("art", "");
                    if (!art.equals(radioArt)) {
                        radioArt = art;
                        loadArt(art);
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private void loadArt(String url) {
        try {
            BufferedImage src = Radio.isArt(url) ? ImageIO.read(new File(url)) : null;
            if (src == null) {
                artPending = null;
                return;
            }
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, 64, 64, null);
            g.dispose();
            artPending = img;
        } catch (Exception ignored) {
        }
    }

    private void uploadArt() {
        BufferedImage img = artPending;
        if (img == null) return;
        artPending = null;
        ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) {
                int p = img.getRGB(x, y);
                pixels.put((byte) (p >> 16))
                        .put((byte) (p >> 8))
                        .put((byte) p)
                        .put((byte) (p >> 24));
            }
        pixels.flip();
        if (artTexture == 0) artTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, artTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                64,
                64,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                pixels);
    }

    private String fit(String s, float max) {
        if (width(s) <= max) return s;
        while (s.length() > 1 && width(s + "...") > max) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private void radioOverlay(float sw, float sh) {
        float w = 160, h = 44;
        boolean art = flag("radio", "art");
        place("radio", w, h, sw - w - 8, 8, sw, sh);
        uploadArt();
        if (flag("radio", "bg")) rect(0, 0, w, h, 0xE6121212);
        if (flag("radio", "bar")) rect(0, 0, 2, h, RED);
        float tx = 8;
        if (art) {
            if (artTexture != 0 && !radioArt.isEmpty() && !radioTitle.isEmpty()) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, artTexture);
                color(0xFFFFFFFF);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0, 0);
                GL11.glVertex2f(6, 6);
                GL11.glTexCoord2f(1, 0);
                GL11.glVertex2f(38, 6);
                GL11.glTexCoord2f(1, 1);
                GL11.glVertex2f(38, 38);
                GL11.glTexCoord2f(0, 1);
                GL11.glVertex2f(6, 38);
                GL11.glEnd();
            } else rect(6, 6, 32, 32, 0xFF2A2A2A);
            tx = 44;
        }
        String title = radioTitle, sub = radioArtist;
        if (title.isEmpty()) {
            title = "RÁDIO ANTAGON";
            sub = "Selecione uma rádio ou playlist";
        }
        text(fit(title, w - tx - 6), tx, 5, WHITE);
        text(fit(sub, w - tx - 6), tx, 17, GRAY);
        boolean playing = radioState.equals("playing");
        float pos = radioPos + (playing ? (System.currentTimeMillis() - radioStamp) / 1000f : 0),
                progress = radioDur > 0 ? Math.min(1, pos / radioDur) : 0;
        rect(tx, 34, w - tx - 8, 2, 0xFF3A3A3A);
        rect(tx, 34, (w - tx - 8) * progress, 2, playing ? RED : GRAY);
        GL11.glPopMatrix();
    }

    @SubscribeEvent
    public void attack(AttackEntityEvent event) {
        try {
            if (field(event, "entityPlayer") != field(mc, "field_71439_g", "thePlayer")) return;
            if (enabled("combo")) {
                pendingTarget = field(event, "target");
                pendingAt = System.currentTimeMillis();
            }
            if (!enabled("reach")) return;
            Object hit = field(mc, "field_71476_x", "objectMouseOver");
            if (hit == null) return;
            Object vec = field(hit, "field_72307_f", "hitVec");
            if (vec == null) return;
            Object eyes =
                    call(
                            field(mc, "field_71439_g", "thePlayer"),
                            new String[] {"func_174824_e", "getPositionEyes"},
                            1f);
            lastReach =
                    ((Number) call(eyes, new String[] {"func_72438_d", "distanceTo"}, vec))
                            .doubleValue();
            lastReachAt = System.currentTimeMillis();
        } catch (Exception e) {
            report(e);
        }
    }

    private void comboTick() {
        if (!enabled("combo")) {
            combo = 0;
            pendingTarget = null;
            return;
        }
        try {
            Object player = field(mc, "field_71439_g", "thePlayer");
            if (player == null) {
                combo = 0;
                return;
            }
            int hurt = ((Number) field(player, "field_70737_aN", "hurtTime")).intValue();
            if (hurt > lastHurt) combo = 0;
            lastHurt = hurt;
            long now = System.currentTimeMillis();
            if (pendingTarget != null) {
                try {
                    if (((Number) field(pendingTarget, "field_70737_aN", "hurtTime")).intValue()
                            >= 9) {
                        combo++;
                        comboAt = now;
                        pendingTarget = null;
                    }
                } catch (NoSuchFieldException notLiving) {
                    pendingTarget = null;
                }
                if (now - pendingAt > 600) pendingTarget = null;
            }
            if (combo > 0 && now - comboAt > 3000) combo = 0;
        } catch (Exception e) {
            report(e);
        }
    }

    private void hitColor() {
        if (!enabled("hitcolor")) {
            HitColor.r = 1;
            HitColor.g = 0;
            HitColor.b = 0;
            HitColor.a = .3f;
            return;
        }
        int c = colorOf(opt("hitcolor", "color"));
        HitColor.r = (c >> 16 & 255) / 255f;
        HitColor.g = (c >> 8 & 255) / 255f;
        HitColor.b = (c & 255) / 255f;
        HitColor.a = percent("hitcolor", "alpha");
    }

    @SubscribeEvent
    public void hitboxes(RenderWorldLastEvent event) {
        if (!enabled("hitbox")) return;
        try {
            Object world = field(mc, "field_71441_e", "theWorld"),
                    self = field(mc, "field_71439_g", "thePlayer");
            if (world == null || self == null) return;
            Object rm = call(mc, new String[] {"func_175598_ae", "getRenderManager"});
            double vx = ((Number) field(rm, "field_78725_b", "renderPosX")).doubleValue(),
                    vy = ((Number) field(rm, "field_78726_c", "renderPosY")).doubleValue(),
                    vz = ((Number) field(rm, "field_78723_d", "renderPosZ")).doubleValue();
            boolean firstPerson =
                    ((Number)
                                            field(
                                                    field(mc, "field_71474_y", "gameSettings"),
                                                    "field_74320_O",
                                                    "thirdPersonView"))
                                    .intValue()
                            == 0;
            Class<?> players = Class.forName("net.minecraft.entity.player.EntityPlayer"),
                    living = Class.forName("net.minecraft.entity.EntityLivingBase");
            boolean onlyPlayers = flag("hitbox", "players");
            double margin = flag("hitbox", "margin") ? .1 : 0;
            float pt = event.partialTicks;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL11.glLineWidth(Integer.parseInt(opt("hitbox", "thick")));
            color(colorOf(opt("hitbox", "color")));
            GL11.glBegin(GL11.GL_LINES);
            for (Object e :
                    new ArrayList<Object>(
                            (List<?>) field(world, "field_72996_f", "loadedEntityList"))) {
                if (e == self && firstPerson
                        || !(onlyPlayers ? players : living).isInstance(e)
                        || (Boolean) call(e, new String[] {"func_82150_aj", "isInvisible"}))
                    continue;
                Object bb = call(e, new String[] {"func_174813_aQ", "getEntityBoundingBox"});
                double ox = lerp(e, "field_70142_S", "field_70165_t", pt) - vx,
                        oy = lerp(e, "field_70137_T", "field_70163_u", pt) - vy,
                        oz = lerp(e, "field_70136_U", "field_70161_v", pt) - vz;
                double[] b = {
                    d(bb, "field_72340_a") - margin + ox,
                    d(bb, "field_72338_b") - margin + oy,
                    d(bb, "field_72339_c") - margin + oz,
                    d(bb, "field_72336_d") + margin + ox,
                    d(bb, "field_72337_e") + margin + oy,
                    d(bb, "field_72334_f") + margin + oz
                };
                int[][] edges = {
                    {0, 1, 2, 3, 1, 2},
                    {0, 1, 2, 0, 1, 5},
                    {0, 1, 5, 3, 1, 5},
                    {3, 1, 2, 3, 1, 5},
                    {0, 4, 2, 3, 4, 2},
                    {0, 4, 2, 0, 4, 5},
                    {0, 4, 5, 3, 4, 5},
                    {3, 4, 2, 3, 4, 5},
                    {0, 1, 2, 0, 4, 2},
                    {3, 1, 2, 3, 4, 2},
                    {0, 1, 5, 0, 4, 5},
                    {3, 1, 5, 3, 4, 5}
                };
                for (int[] l : edges) {
                    GL11.glVertex3d(b[l[0]], b[l[1]], b[l[2]]);
                    GL11.glVertex3d(b[l[3]], b[l[4]], b[l[5]]);
                }
            }
            GL11.glEnd();
            GL11.glPopAttrib();
        } catch (Exception e) {
            report(e);
        }
    }

    private void sprint() {
        try {
            Object settings = field(mc, "field_71474_y", "gameSettings");
            Object bind = field(settings, "field_151444_V", "keyBindSprint");
            int code =
                    ((Number) call(bind, new String[] {"func_151463_i", "getKeyCode"})).intValue();
            if (!enabled("togglesprint")) {
                if (sprintToggled) {
                    sprintToggled = false;
                    invoke(
                            bind.getClass(),
                            null,
                            new String[] {"func_74510_a", "setKeyBindState"},
                            code,
                            false);
                }
                return;
            }
            boolean down =
                    field(mc, "field_71462_r", "currentScreen") == null
                            && (code < 0
                                    ? Mouse.isButtonDown(code + 100)
                                    : code > 0 && Keyboard.isKeyDown(code));
            if (down && !sprintWas) sprintToggled = !sprintToggled;
            sprintWas = down;
            if (sprintToggled)
                invoke(
                        bind.getClass(),
                        null,
                        new String[] {"func_74510_a", "setKeyBindState"},
                        code,
                        true);
        } catch (Exception e) {
            report(e);
        }
    }

    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent event) {
        try {
            Object player = field(mc, "field_71439_g", "thePlayer");
            boolean start = event.phase == TickEvent.Phase.START;

            if (player != null && enabled("nohurtcam")) {
                if (start) {
                    savedHurt = ((Number) field(player, "field_70737_aN", "hurtTime")).intValue();
                    setField(player, 0, "field_70737_aN", "hurtTime");
                } else if (savedHurt > 0) {
                    setField(player, savedHurt, "field_70737_aN", "hurtTime");
                    savedHurt = 0;
                }
            }

            if (start && enabled("notitles")) {
                Object gui = field(mc, "field_71456_v", "ingameGUI");
                call(gui, new String[] {"func_175178_a", "displayTitle"}, null, null, -1, -1, -1);
                if (flag("notitles", "actionbar"))
                    setField(gui, 0, "field_73845_h", "recordPlayingUpFor");
            }
            perspective(player, start);
        } catch (Exception e) {
            report(e);
        }
    }

    private void perspective(Object player, boolean start) throws Exception {
        Object settings = field(mc, "field_71474_y", "gameSettings");
        if (start) {
            boolean want = false;
            if (player != null && (enabled("perspective") || smokeLook)) {
                boolean down = false;
                if (field(mc, "field_71462_r", "currentScreen") == null) {
                    int k = keyOf("perspective", "key");
                    down = isDown(k);
                }
                if (opt("perspective", "mode").equals("alternar") || smokeLook) {
                    if (down && !lookKeyWas) lookToggled = !lookToggled;
                    want = lookToggled || smokeLook;
                } else want = down;
                lookKeyWas = down;
            } else lookToggled = false;
            if (want && !looking) {
                looking = true;
                camYaw = getF(player, YAW);
                camPitch = getF(player, PITCH);
                savedView =
                        ((Number) field(settings, "field_74320_O", "thirdPersonView")).intValue();
                setField(settings, 1, "field_74320_O", "thirdPersonView");
            }
            if (!want && looking) {
                looking = false;
                setField(settings, savedView, "field_74320_O", "thirdPersonView");
            }
            if (looking) {
                savedYaw = getF(player, YAW);
                savedPitch = getF(player, PITCH);
                savedPrevYaw = getF(player, PREV_YAW);
                savedPrevPitch = getF(player, PREV_PITCH);
                setF(player, YAW, camYaw);
                setF(player, PREV_YAW, camYaw);
                setF(player, PITCH, camPitch);
                setF(player, PREV_PITCH, camPitch);
            }
        } else if (looking && player != null) {
            camYaw = getF(player, YAW);
            camPitch = Math.max(-90, Math.min(90, getF(player, PITCH)));
            setF(player, YAW, savedYaw);
            setF(player, PITCH, savedPitch);
            setF(player, PREV_YAW, savedPrevYaw);
            setF(player, PREV_PITCH, savedPrevPitch);
        }
    }

    private void shot(String name) throws Exception {
        Object framebuffer = call(mc, new String[] {"func_147110_a", "getFramebuffer"});
        int w = ((Number) field(mc, "field_71443_c", "displayWidth")).intValue(),
                h = ((Number) field(mc, "field_71440_d", "displayHeight")).intValue();
        invoke(
                Class.forName("net.minecraft.util.ScreenShotHelper"),
                null,
                new String[] {"func_148259_a", "saveScreenshot"},
                new File("."),
                name,
                w,
                h,
                framebuffer);
        System.out.println("[ANTAGON TEST] Screenshot saved " + name);
    }

    private void smoke() {
        try {
            if (!smokeStarted && smokeTicks == 35) shot("antagon-title-buttons.png");
            if (!smokeStarted && ++smokeTicks > 80) {
                smokeStarted = true;
                Class<?> ws = Class.forName("net.minecraft.world.WorldSettings");
                Class<?> mode = Class.forName("net.minecraft.world.WorldSettings$GameType");
                Class<?> wt = Class.forName("net.minecraft.world.WorldType");
                Object creative = Enum.valueOf((Class) mode, "CREATIVE");
                Field flat = wt.getDeclaredField("field_77138_c");
                flat.setAccessible(true);
                Object options =
                        ws.getConstructor(long.class, mode, boolean.class, boolean.class, wt)
                                .newInstance(42L, creative, false, false, flat.get(null));
                call(
                        mc,
                        new String[] {"func_71371_a", "launchIntegratedServer"},
                        "Antagon-Smoke",
                        "Antagon - Smoke",
                        options);
                System.out.println("[ANTAGON TEST] Started local world");
            }
            if (field(mc, "field_71439_g", "thePlayer") == null) return;
            switch (++worldTicks) {
                case 10:
                    Object enteringPlayer = field(mc, "field_71439_g", "thePlayer");
                    if (Cosmetics.cape(null, enteringPlayer) == null)
                        throw new IllegalStateException(
                                "Capa pré-carregada ausente na entrada do mundo");
                    Object enteringProfile =
                            call(enteringPlayer, new String[] {"func_146103_bH", "getGameProfile"});
                    String enteringId =
                            call(enteringProfile, new String[] {"getId"})
                                    .toString()
                                    .replace("-", "");
                    if (!new String(
                                    Files.readAllBytes(new File("antagon-players.txt").toPath()),
                                    StandardCharsets.UTF_8)
                            .contains(enteringId))
                        throw new IllegalStateException(
                                "Lista de jogadores atrasada na entrada do mundo");
                    System.out.println("[ANTAGON TEST] Cosmetics and roster ready on world entry");
                    break;
                case 140:
                    setField(
                            field(mc, "field_71474_y", "gameSettings"),
                            1,
                            "field_74320_O",
                            "thirdPersonView");
                    break;
                case 145:
                    shot("antagon-cape-test.png");
                    break;
                case 150:
                    setField(
                            field(mc, "field_71474_y", "gameSettings"),
                            0,
                            "field_74320_O",
                            "thirdPersonView");
                    break;
                case 160:
                    call(
                            mc,
                            new String[] {"func_147108_a", "displayGuiScreen"},
                            Class.forName("net.minecraft.client.gui.GuiIngameMenu").newInstance());
                    break;
                case 165:
                    shot("antagon-pause-buttons.png");
                    break;
                case 166:
                    {
                        Object screen = field(mc, "field_71462_r", "currentScreen");
                        @SuppressWarnings("unchecked")
                        List<Object> buttons =
                                (List<Object>) field(screen, "field_146292_n", "buttonList");
                        for (Object button : buttons)
                            if (((Number) field(button, "field_146127_k", "id")).intValue()
                                    == STORE_BUTTON_ID) {
                                int x =
                                        ((Number) field(button, "field_146128_h", "xPosition"))
                                                .intValue();
                                int y =
                                        ((Number) field(button, "field_146129_i", "yPosition"))
                                                .intValue();
                                call(
                                        screen,
                                        new String[] {"func_73864_a", "mouseClicked"},
                                        x + 10,
                                        y + 10,
                                        0);
                                break;
                            }
                    }
                    break;
                case 167:
                    {
                        Object open = field(mc, "field_71462_r", "currentScreen");
                        if (!(open instanceof Hub) || !((Hub) open).tab.equals("store"))
                            throw new IllegalStateException(
                                    "O botão da loja não abriu a loja no jogo");
                        System.out.println("[ANTAGON TEST] Store button opened the in-game store");
                    }
                    break;
                case 170:
                    call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, (Object) null);
                    break;
                case 180:
                    shot("antagon-smoke.png");
                    break;
                case 190:
                    smokeConfig = true;
                    openMenu();
                    break;
                case 210:
                    shot("antagon-menu.png");
                    break;
                case 211:
                    menu.scroll =
                            menu.scrollTarget =
                                    MenuLayout.maxScroll(menu.visibleMods.length, menu.ph());
                    break;
                case 214:
                    shot("antagon-menu-bottom.png");
                    menu.setCategory(2);
                    break;
                case 220:
                    shot("antagon-menu-pvp.png");
                    menu.page = "crosshair";
                    break;
                case 230:
                    shot("antagon-options.png");
                    menu.page = "cps";
                    break;
                case 231:
                    shot("antagon-cps-options.png");
                    break;
                case 232:
                    if (menu != null) {
                        menu.page = null;
                        menu.editing = true;
                    }
                    break;
                case 246:
                    menu.drag = "fps";
                    menu.dx = 0;
                    menu.dy = 0;
                    menu.func_146273_a(203, 101, 0, 0);
                    break;
                case 250:
                    shot("antagon-edit.png");
                    break;
                case 252:
                    if (menu != null) menu.close();
                    smokeLook = true;
                    break;
                case 262:
                    setF(
                            field(mc, "field_71439_g", "thePlayer"),
                            YAW,
                            getF(field(mc, "field_71439_g", "thePlayer"), YAW) + 120);
                    break;
                case 280:
                    shot("antagon-perspective.png");
                    break;
                case 282:
                    smokeLook = false;
                    break;
                case 300:
                    shot("antagon-after-perspective.png");
                    break;
                case 304:
                    config.setProperty("autotext", "true");
                    config.setProperty("autotext.1.text", "Antagon auto text ok");
                    config.setProperty("autotext.1.key", "MOUSE5");
                    config.setProperty("autotext.2.text", "/gamemode 1");
                    boundKey(-96);
                    openMenu();
                    menu.page = "autotext";
                    menu.typing = "2";
                    break;
                case 316:
                    shot("antagon-autotext.png");
                    menu.close();
                    break;
                case 320:
                    smokeConfig = true;
                    config.setProperty("crosshair", "true");
                    config.setProperty("fps.size", "150%");
                    config.setProperty("chat", "true");
                    config.setProperty("chat.time", "on");
                    config.setProperty("notitles", "true");
                    for (int i = 0; i < 3; i++)
                        MinecraftForge.EVENT_BUS.post(
                                (net.minecraftforge.fml.common.eventhandler.Event)
                                        ClientChatReceivedEvent.class
                                                .getConstructor(
                                                        byte.class,
                                                        Class.forName(
                                                                "net.minecraft.util.IChatComponent"))
                                                .newInstance(
                                                        (byte) 0,
                                                        Class.forName(
                                                                        "net.minecraft.util.ChatComponentText")
                                                                .getConstructor(String.class)
                                                                .newInstance("Antagon chat test")));
                    call(
                            field(mc, "field_71456_v", "ingameGUI"),
                            new String[] {"func_175178_a", "displayTitle"},
                            "TITULO",
                            null,
                            10,
                            70,
                            20);
                    break;
                case 330:
                    shot("antagon-new-mods.png");
                    break;
                case 340:
                    config.setProperty("reach", "true");
                    lastReach = 2.87;
                    lastReachAt = System.currentTimeMillis() + 60000;
                    config.setProperty("radio", "true");
                    config.setProperty("radio.size", "125%");
                    radioTitle = "Música de teste com um nome bem comprido";
                    radioArtist = "Antagon";
                    radioState = "playing";
                    radioDur = 200;
                    radioPos = 80;
                    radioStamp = System.currentTimeMillis();
                    openMenu();
                    menu.editing = true;
                    break;
                case 360:
                    shot("antagon-radio.png");
                    break;
                case 362:
                    menu.editing = false;
                    menu.page = "radio";
                    break;
                case 378:
                    menu.binding = "radio.next";
                    menu.func_73864_a(0, 0, 4);
                    System.out.println(
                            "[ANTAGON TEST] mouse bind "
                                    + config.getProperty("radio.next")
                                    + " -> "
                                    + keyOf("radio", "next"));
                    break;
                case 380:
                    shot("antagon-radio-menu.png");
                    break;
                case 382:
                    menu.close();
                    config.setProperty("combo", "true");
                    config.setProperty("hitbox", "true");
                    config.setProperty("hitcolor", "true");
                    config.setProperty("hitcolor.color", "ciano");
                    config.setProperty("hitcolor.alpha", "60%");
                    combo = 3;
                    comboAt = System.currentTimeMillis() + 60000;
                    {
                        final Object self = field(mc, "field_71439_g", "thePlayer");
                        final Object server =
                                call(mc, new String[] {"func_71401_C", "getIntegratedServer"});
                        final double px = d(self, "field_70165_t"),
                                py = d(self, "field_70163_u"),
                                pz = d(self, "field_70161_v");
                        call(
                                server,
                                new String[] {"func_152344_a", "addScheduledTask"},
                                (Runnable)
                                        () -> {
                                            try {
                                                Object ws =
                                                        call(
                                                                server,
                                                                new String[] {
                                                                    "func_71218_a",
                                                                    "worldServerForDimension"
                                                                },
                                                                0);
                                                Object pig =
                                                        Class.forName(
                                                                        "net.minecraft.entity.passive.EntityPig")
                                                                .getConstructor(
                                                                        Class.forName(
                                                                                "net.minecraft.world.World"))
                                                                .newInstance(ws);
                                                call(
                                                        pig,
                                                        new String[] {
                                                            "func_70012_b", "setLocationAndAngles"
                                                        },
                                                        px + 1.5,
                                                        py,
                                                        pz + 3.5,
                                                        0f,
                                                        0f);
                                                call(
                                                        ws,
                                                        new String[] {
                                                            "func_72838_d", "spawnEntityInWorld"
                                                        },
                                                        pig);
                                            } catch (Exception e) {
                                                report(e);
                                            }
                                        });
                    }
                    break;
                case 384:
                case 386:
                case 388:
                case 390:
                case 392:
                case 394:
                case 396:
                case 398:
                case 400:
                    {
                        Object self = field(mc, "field_71439_g", "thePlayer"), target = null;
                        double best = 400;
                        for (Object e :
                                (List<?>)
                                        field(
                                                field(mc, "field_71441_e", "theWorld"),
                                                "field_72996_f",
                                                "loadedEntityList")) {
                            if (e == self
                                    || !Class.forName("net.minecraft.entity.EntityLivingBase")
                                            .isInstance(e)) continue;
                            double dx = d(e, "field_70165_t") - d(self, "field_70165_t"),
                                    dz = d(e, "field_70161_v") - d(self, "field_70161_v");
                            if (dx * dx + dz * dz < best) {
                                best = dx * dx + dz * dz;
                                target = e;
                            }
                        }
                        if (target != null) {
                            double dx = d(target, "field_70165_t") - d(self, "field_70165_t"),
                                    dz = d(target, "field_70161_v") - d(self, "field_70161_v"),
                                    dy = d(target, "field_70163_u") - d(self, "field_70163_u");
                            setF(self, YAW, (float) Math.toDegrees(Math.atan2(-dx, dz)));
                            setF(
                                    self,
                                    PITCH,
                                    (float)
                                            -Math.toDegrees(
                                                    Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                            setField(target, 10, "field_70737_aN", "hurtTime");
                        }
                        if (worldTicks == 400) shot("antagon-combat.png");
                        break;
                    }
                case 404:
                    {
                        Object board =
                                call(
                                        field(mc, "field_71441_e", "theWorld"),
                                        new String[] {"func_96441_U", "getScoreboard"});
                        Object dummy =
                                Class.forName("net.minecraft.scoreboard.IScoreObjectiveCriteria")
                                        .getField("field_96641_b")
                                        .get(null);
                        Object objective =
                                call(
                                        board,
                                        new String[] {"func_96535_a", "addScoreObjective"},
                                        "antagon",
                                        dummy);
                        call(
                                objective,
                                new String[] {"func_96681_a", "setDisplayName"},
                                "\u00a7e\u00a7lBED WARS");
                        call(
                                board,
                                new String[] {"func_96530_a", "setObjectiveInDisplaySlot"},
                                1,
                                objective);
                        String[] rows = {
                            "\u00a77Mapa: Lighthouse",
                            " ",
                            "\u00a7cR \u00a7fVermelho \u00a7a\u2714",
                            "\u00a79B \u00a7fAzul \u00a7a\u2714",
                            "  ",
                            "\u00a7fKills: \u00a7a3",
                            "\u00a7eantagon.studio"
                        };
                        for (int i = 0; i < rows.length; i++)
                            call(
                                    call(
                                            board,
                                            new String[] {"func_96529_a", "getValueFromObjective"},
                                            rows[i],
                                            objective),
                                    new String[] {"func_96647_c", "setScorePoints"},
                                    rows.length - i);
                        config.setProperty("scoreboard", "true");
                        config.setProperty("hitdelay", "true");
                    }
                    break;
                case 412:
                    shot("antagon-scoreboard.png");
                    config.setProperty("scoreboard.bg", "off");
                    config.setProperty("scoreboard.numbers", "off");
                    config.setProperty("scoreboard.size", "80%");
                    break;
                case 420:
                    shot("antagon-scoreboard-clean.png");
                    System.out.println(
                            "[ANTAGON TEST] leftClickCounter " + field(mc, "field_71429_W"));
                    break;
                case 430:
                    final Object night =
                            call(mc, new String[] {"func_71401_C", "getIntegratedServer"});
                    call(
                            night,
                            new String[] {"func_152344_a", "addScheduledTask"},
                            (Runnable)
                                    () -> {
                                        try {
                                            call(
                                                    call(
                                                            night,
                                                            new String[] {
                                                                "func_71218_a",
                                                                "worldServerForDimension"
                                                            },
                                                            0),
                                                    new String[] {"func_72877_b", "setWorldTime"},
                                                    18000L);
                                        } catch (Exception e) {
                                            report(e);
                                        }
                                    });
                    break;
                case 450:
                    shot("antagon-night.png");
                    config.setProperty("fullbright", "true");
                    break;
                case 460:
                    shot("antagon-fullbright.png");
                    config.setProperty("fullbright", "false");
                    break;
                case 470:
                    System.out.println(
                            "[ANTAGON TEST] gamma restored "
                                    + getF(field(mc, "field_71474_y"), "field_74333_Y"));
                    break;
                case 480:
                    {
                        Object settings = field(mc, "field_71474_y");
                        Object tab = field(settings, "field_74321_H");
                        invoke(
                                tab.getClass(),
                                null,
                                new String[] {"func_74510_a", "setKeyBindState"},
                                ((Number) call(tab, new String[] {"func_151463_i", "getKeyCode"}))
                                        .intValue(),
                                true);
                        Object board =
                                call(
                                        field(mc, "field_71441_e", "theWorld"),
                                        new String[] {"func_96441_U", "getScoreboard"});
                        call(
                                board,
                                new String[] {"func_96530_a", "setObjectiveInDisplaySlot"},
                                0,
                                call(
                                        board,
                                        new String[] {"func_96518_b", "getObjective"},
                                        "antagon"));
                        config.setProperty("scoreboard", "true");
                        config.setProperty("scoreboard.bg", "on");
                        config.setProperty("scoreboard.size", "100%");
                        config.setProperty("scoreboard.numbers", "on");
                    }
                    break;
                case 490:
                    shot("antagon-tab-on.png");
                    config.setProperty("scoreboard", "false");
                    break;
                case 500:
                    shot("antagon-tab-off.png");
                    break;
                case 590:
                    setField(
                            field(mc, "field_71474_y", "gameSettings"),
                            1,
                            "field_74320_O",
                            "thirdPersonView");
                    break;
                case 600:
                    shot("antagon-cape-late.png");
                    break;
                case 610:
                    setField(
                            field(mc, "field_71474_y", "gameSettings"),
                            0,
                            "field_74320_O",
                            "thirdPersonView");
                    break;
                case 620:
                    {
                        Object player = field(mc, "field_71439_g", "thePlayer");
                        Object profile =
                                call(player, new String[] {"func_146103_bH", "getGameProfile"});
                        String uuid =
                                call(profile, new String[] {"getId"}).toString().replace("-", "");
                        BufferedImage texture =
                                new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics2D g = texture.createGraphics();
                        g.setColor(new java.awt.Color(0x14285A));
                        g.fillRect(0, 0, 352, 272);
                        g.setColor(new java.awt.Color(0x2A5BD7));
                        g.fillRect(16, 16, 160, 256);
                        g.setColor(java.awt.Color.WHITE);
                        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 44));
                        g.drawString("TESTE", 26, 150);
                        g.dispose();
                        new File("antagon-capes").mkdirs();
                        ImageIO.write(
                                texture,
                                "png",
                                new File("antagon-capes/custom_0123456789abcdef.png"));
                        Files.write(
                                new File("antagon-cosmetics.properties").toPath(),
                                (uuid
                                                + "=client,cape:custom_0123456789abcdef,hat:antagon_crown,admin\n")
                                        .getBytes(StandardCharsets.UTF_8));
                        setField(
                                field(mc, "field_71474_y", "gameSettings"),
                                1,
                                "field_74320_O",
                                "thirdPersonView");
                        config.setProperty("fullbright", "true");
                    }
                    break;
                case 640:
                    shot("antagon-logo-cape.png");
                    setField(
                            field(mc, "field_71474_y", "gameSettings"),
                            2,
                            "field_74320_O",
                            "thirdPersonView");
                    break;
                case 645:
                    shot("antagon-crown.png");
                    config.setProperty("fullbright", "false");
                    setField(
                            field(mc, "field_71474_y", "gameSettings"),
                            0,
                            "field_74320_O",
                            "thirdPersonView");
                    call(
                            mc,
                            new String[] {"func_147108_a", "displayGuiScreen"},
                            Class.forName("net.minecraft.client.gui.GuiIngameMenu").newInstance());
                    break;
                case 650:
                    shot("antagon-pause.png");
                    {
                        String friend = "11111111-2222-3333-4444-555555555555";
                        String state =
                                "updated="
                                        + System.currentTimeMillis()
                                        + "\nonline=1\nme=AntagonTest\nnotice=\n"
                                        + "friend="
                                        + friend
                                        + "\tAmigoTeste\t1\tserver\tmc.hypixel.net\t0\n"
                                        + "friend=99999999-2222-3333-4444-555555555555"
                                        + "\tOfflineTeste\t0\toffline\t\t0\n"
                                        + "incoming=88888888-2222-3333-4444-555555555555\tNovato\n"
                                        + "chat="
                                        + friend
                                        + "\n"
                                        + "msg=0\t19:02\tbora um bedwars?\n"
                                        + "msg=1\t19:03\tbora! me chama no servidor\n"
                                        + "balance=250\n"
                                        + "item=antagon_cape\tCapa Antagon\t100\t0\t0\t1\tcape\n"
                                        + "item=antagon_logo_cape\tCapa Logo Antagon\t100\t1\t0\t0"
                                        + "\tcape\n"
                                        + "item=antagon_crown\tCoroa Antagon\t250\t1\t1\t1\that\n"
                                        + "item=custom_0123456789abcdef\tCapa Teste\t0\t1\t1\t0"
                                        + "\tcape\n";
                        Files.write(
                                new File("antagon-ui-state.txt").toPath(),
                                state.getBytes(StandardCharsets.UTF_8));
                        Hub hub = new Hub(null, "friends");
                        hub.chat = friend;
                        smokeHub = hub;
                        call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, hub);
                    }
                    break;
                case 660:
                    shot("antagon-hub-friends.png");
                    smokeHub.tab = "store";
                    break;
                case 670:
                    shot("antagon-hub-store.png");
                    smokeHub.tab = "inventory";
                    break;
                case 673:
                    shot("antagon-hub-inventory.png");
                    Files.write(
                            new File("antagon-notify-" + System.currentTimeMillis() + "0000.txt")
                                    .toPath(),
                            "accepted\tAmigoTeste\taceitou seu pedido de amizade"
                                    .getBytes(StandardCharsets.UTF_8));
                    call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, (Object) null);
                    break;
                case 675:
                    {
                        final Object self = field(mc, "field_71439_g", "thePlayer");
                        final Object server =
                                call(mc, new String[] {"func_71401_C", "getIntegratedServer"});
                        float yaw = getF(self, YAW);
                        final double
                                ix = d(self, "field_70165_t") - Math.sin(Math.toRadians(yaw)) * 1.6,
                                iy = d(self, "field_70163_u") + 0.5,
                                iz = d(self, "field_70161_v") + Math.cos(Math.toRadians(yaw)) * 1.6;
                        call(
                                server,
                                new String[] {"func_152344_a", "addScheduledTask"},
                                (Runnable)
                                        () -> {
                                            try {
                                                Object ws =
                                                        call(
                                                                server,
                                                                new String[] {
                                                                    "func_71218_a",
                                                                    "worldServerForDimension"
                                                                },
                                                                0);
                                                Class<?>
                                                        item =
                                                                Class.forName(
                                                                        "net.minecraft.item.Item"),
                                                        stack =
                                                                Class.forName(
                                                                        "net.minecraft.item.ItemStack"),
                                                        entity =
                                                                Class.forName(
                                                                        "net.minecraft.entity.item.EntityItem");
                                                Object diamond =
                                                        Class.forName("net.minecraft.init.Items")
                                                                .getField("field_151045_i")
                                                                .get(null);
                                                Object gold =
                                                        invoke(
                                                                item,
                                                                null,
                                                                new String[] {
                                                                    "func_150898_a",
                                                                    "getItemFromBlock"
                                                                },
                                                                Class.forName(
                                                                                "net.minecraft.init.Blocks")
                                                                        .getField("field_150340_R")
                                                                        .get(null));
                                                Object[] drops = {
                                                    stack.getConstructor(item, int.class)
                                                            .newInstance(diamond, 16),
                                                    stack.getConstructor(item, int.class)
                                                            .newInstance(gold, 8)
                                                };
                                                for (int i = 0; i < drops.length; i++) {
                                                    Object e =
                                                            entity.getConstructor(
                                                                            Class.forName(
                                                                                    "net.minecraft.world.World"),
                                                                            double.class,
                                                                            double.class,
                                                                            double.class,
                                                                            stack)
                                                                    .newInstance(
                                                                            ws,
                                                                            ix + i * 0.8,
                                                                            iy,
                                                                            iz,
                                                                            drops[i]);
                                                    setField(
                                                            e,
                                                            32767,
                                                            "field_145804_b",
                                                            "delayBeforeCanPickup");
                                                    call(
                                                            ws,
                                                            new String[] {
                                                                "func_72838_d", "spawnEntityInWorld"
                                                            },
                                                            e);
                                                }
                                            } catch (Exception e) {
                                                report(e);
                                            }
                                        });
                        config.setProperty("itemphysics", "true");
                        config.setProperty("fullbright", "true");
                        setF(self, PITCH, 60f);
                    }
                    break;
                case 690:
                    shot("antagon-notice.png");
                    break;
                case 695:
                    shot("antagon-item-physics.png");
                    break;
                case 700:
                    {
                        if ("1".equals(System.getenv("ANTAGON_TEST_RADIO"))) NativeRadio.open();
                        Object self = field(mc, "field_71439_g", "thePlayer");
                        setF(self, PITCH, 0f);
                        Object inventory = field(self, "field_71071_by", "inventory");
                        Object[] items =
                                (Object[]) field(inventory, "field_70462_a", "mainInventory");
                        Object sword =
                                Class.forName("net.minecraft.init.Items")
                                        .getField("field_151048_u")
                                        .get(null);
                        items[0] =
                                Class.forName("net.minecraft.item.ItemStack")
                                        .getConstructor(
                                                Class.forName("net.minecraft.item.Item"), int.class)
                                        .newInstance(sword, 1);
                        setField(inventory, 0, "field_70461_c", "currentItem");
                        config.setProperty("itemsize", "true");
                        config.setProperty("itemsize.scale", "50%");
                        Hooks.heldItemTransforms = 0;
                    }
                    break;
                case 720:
                    if (Hooks.heldItemTransforms == 0 || Hooks.heldItemScale != .5f)
                        throw new IllegalStateException("Item Size did not resize the held model");
                    shot("antagon-item-size-small.png");
                    config.setProperty("itemsize", "false");
                    break;
                case 725:
                    Hooks.heldItemTransforms = 0;
                    break;
                case 735:
                    if (Hooks.heldItemTransforms != 0 || Hooks.heldItemScale != 1f)
                        throw new IllegalStateException("Disabled Item Size still changes models");
                    shot("antagon-item-size-default.png");
                    openMenu();
                    menu.page = "itemsize";
                    break;
                case 745:
                    shot("antagon-item-size-options.png");
                    System.out.println(
                            "[ANTAGON TEST] Item Size renders the configured scale and restores"
                                    + " vanilla when disabled");
                    break;
                case 750:
                    {
                        call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, (Object) null);
                        Object self = field(mc, "field_71439_g", "thePlayer");
                        Object inv = field(self, "field_71071_by", "inventory");
                        Object[] armor = (Object[]) field(inv, "field_70460_b", "armorInventory");
                        for (int i = 0; i < 4; i++) {
                            armor[i] = StatusData.item(313 - i);
                            call(
                                    armor[i],
                                    new String[] {"func_77964_b", "setItemDamage"},
                                    20 + i * 50);
                        }
                        Class<?> potionEffect = Class.forName("net.minecraft.potion.PotionEffect");
                        call(
                                self,
                                new String[] {"func_70690_d", "addPotionEffect"},
                                potionEffect
                                        .getConstructor(int.class, int.class, int.class)
                                        .newInstance(1, 2400, 1));
                        call(
                                self,
                                new String[] {"func_70690_d", "addPotionEffect"},
                                potionEffect
                                        .getConstructor(int.class, int.class, int.class)
                                        .newInstance(5, 180, 0));
                        config.setProperty("armor", "true");
                        config.setProperty("potions", "true");
                    }
                    break;
                case 765:
                    {
                        Object self = field(mc, "field_71439_g", "thePlayer");
                        if (StatusData.armor(self, true, false).size() != 5
                                || StatusData.potions(self, false).size() != 2)
                            throw new IllegalStateException(
                                    "Status data did not read the player's equipment/effects");
                        shot("antagon-status-vertical.png");
                        config.setProperty("armor.layout", "horizontal");
                        config.setProperty("armor.durability", "restante");
                    }
                    break;
                case 780:
                    shot("antagon-status-horizontal.png");
                    System.out.println(
                            "[ANTAGON TEST] Armor and potion status read real equipment and"
                                    + " effects");
                    config.setProperty("oldanimations", "true");
                    LegacyAnimations.transforms = 0;
                    break;
                case 785:
                    {
                        Object self = field(mc, "field_71439_g", "thePlayer");
                        Object held = call(self, new String[] {"func_70694_bm", "getHeldItem"});
                        call(self, new String[] {"func_71008_a", "setItemInUse"}, held, 72000);
                        setField(self, .4f, "field_70733_aJ", "swingProgress");
                        setField(self, .4f, "field_70732_aI", "prevSwingProgress");
                        LegacyAnimations.frame(.5f);
                        if (Math.abs(LegacyAnimations.swing(0) - .4f) > .001f)
                            throw new IllegalStateException(
                                    "1.7 block animation did not restore swing");
                        Object inventory = field(self, "field_71071_by", "inventory");
                        Object[] items =
                                (Object[]) field(inventory, "field_70462_a", "mainInventory");
                        for (int item : new int[] {261, 260, 373}) {
                            items[0] = StatusData.item(item);
                            call(
                                    self,
                                    new String[] {"func_71008_a", "setItemInUse"},
                                    items[0],
                                    72000);
                            LegacyAnimations.frame(.5f);
                            if (Math.abs(LegacyAnimations.swing(0) - .4f) > .001f)
                                throw new IllegalStateException(
                                        "1.7 use animation failed for item " + item);
                            LegacyAnimations.bow = LegacyAnimations.eating = false;
                            LegacyAnimations.frame(.5f);
                            if (LegacyAnimations.swing(0) != 0)
                                throw new IllegalStateException(
                                        "Disabled 1.7 option still changes animation");
                            LegacyAnimations.bow = LegacyAnimations.eating = true;
                        }
                        items[0] = held;
                        call(self, new String[] {"func_71008_a", "setItemInUse"}, held, 72000);
                    }
                    break;
                case 800:
                    if (LegacyAnimations.transforms < 2)
                        throw new IllegalStateException("1.7 hook did not run in renderer");
                    shot("antagon-legacy-block.png");
                    config.setProperty("oldanimations", "false");
                    break;
                case 805:
                    LegacyAnimations.transforms = 0;
                    break;
                case 815:
                    if (LegacyAnimations.transforms != 0 || LegacyAnimations.swing(.2f) != .2f)
                        throw new IllegalStateException(
                                "Disabled animations did not restore vanilla");
                    System.out.println(
                            "[ANTAGON TEST] 1.7 animations render and restore vanilla when"
                                    + " disabled");
                    openMenu();
                    menu.editing = true;
                    break;
                case 825:
                    shot("antagon-status-editor.png");
                    break;
                case 830:
                    if (!"1".equals(System.getenv("ANTAGON_TEST_RADIO"))) {
                        call(mc, new String[] {"func_71400_g", "shutdown"});
                        break;
                    }
                    if (NativeRadio.catalog == null)
                        throw new IllegalStateException(
                                "Native radio catalog unavailable: " + NativeRadio.catalogError);
                    for (RadioCatalog.Collection collection : NativeRadio.catalog.collections)
                        if (!collection.live() && collection.name.equalsIgnoreCase("GeekFM")) {
                            NativeRadio.volume(0);
                            NativeRadio.select(collection.id, 0);
                            smokeRadioTrack = NativeRadio.track.id;
                            break;
                        }
                    if (smokeRadioTrack == null)
                        throw new IllegalStateException("GeekFM playlist not found");
                    call(
                            mc,
                            new String[] {"func_147108_a", "displayGuiScreen"},
                            new RadioMenu(null));
                    break;
                case 980:
                    if (!NativeRadio.state.equals("playing") || NativeRadio.position < .5)
                        throw new IllegalStateException(
                                "Native MP3 output did not advance: "
                                        + NativeRadio.state
                                        + " "
                                        + NativeRadio.error);
                    shot("antagon-native-radio.png");
                    NativeRadio.toggle();
                    smokeRadioPosition = NativeRadio.position;
                    break;
                case 990:
                    if (!NativeRadio.state.equals("paused")
                            || Math.abs(NativeRadio.position - smokeRadioPosition) > .3)
                        throw new IllegalStateException("Native radio did not pause");
                    NativeRadio.toggle();
                    NativeRadio.next(1);
                    if (NativeRadio.track.id.equals(smokeRadioTrack))
                        throw new IllegalStateException("Native next did not change track");
                    break;
                case 1100:
                    if (!NativeRadio.state.equals("playing"))
                        throw new IllegalStateException(
                                "Native next track did not start: " + NativeRadio.error);
                    NativeRadio.seek(35);
                    break;
                case 1160:
                    if (!NativeRadio.state.equals("playing") || NativeRadio.position < 35)
                        throw new IllegalStateException(
                                "Native playlist seek failed: " + NativeRadio.position);
                    for (RadioCatalog.Collection collection : NativeRadio.catalog.collections)
                        if (collection.live() && collection.name.equalsIgnoreCase("GeekFM")) {
                            NativeRadio.select(collection.id, 0);
                            break;
                        }
                    break;
                case 1320:
                    RadioCatalog.Live live = NativeRadio.catalog.live(NativeRadio.collection);
                    if (!NativeRadio.state.equals("playing")
                            || live == null
                            || !live.track.id.equals(NativeRadio.track.id)
                            || Math.abs(live.offset - NativeRadio.position) > 1.5)
                        throw new IllegalStateException(
                                "Native live broadcast did not synchronize: "
                                        + NativeRadio.state
                                        + " "
                                        + NativeRadio.error);
                    System.out.println(
                            "[ANTAGON TEST] Native MP3 playback, pause, next, seek and synchronized"
                                    + " live radio work without launcher audio");
                    NativeRadio.stop();
                    call(mc, new String[] {"func_71400_g", "shutdown"});
                    break;
            }
            if (worldTicks % 100 == 0)
                System.out.println(
                        "[ANTAGON TEST] FPS "
                                + invoke(
                                        minecraft,
                                        null,
                                        new String[] {"func_175610_ah", "getDebugFPS"}));
        } catch (Throwable e) {
            System.err.println("[ANTAGON TEST] " + e);
            e.printStackTrace();
            System.clearProperty("antagon.smoke");
        }
    }

    private void begin(float scale) throws Exception {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        font.prepare();
    }

    private void end() {
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private void report(Throwable error) {
        if (!reported) {
            reported = true;
            System.err.println("[ANTAGON] HUD error: " + error);
            error.printStackTrace();
        }
    }

    private void color(int c) {
        GL11.glColor4f(
                (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, (c >>> 24) / 255f);
    }

    private void rect(float x, float y, float w, float h, int c) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        color(c);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void outline(float x, float y, float w, float h, int c) {
        rect(x, y, w, 1, c);
        rect(x, y + h - 1, w, 1, c);
        rect(x, y, 1, h, c);
        rect(x + w - 1, y, 1, h, c);
    }

    private float width(String text) {
        return font.width(text);
    }

    private void text(String text, float x, float y, int color) {
        font.draw(text, x, y, color);
    }

    private void place(
            String key,
            float w,
            float h,
            float defaultX,
            float defaultY,
            float screenW,
            float screenH) {
        float s = percent(key, "size");
        float x = Math.max(0, Math.min(number(key + ".x", defaultX), screenW - w * s)),
                y = Math.max(0, Math.min(number(key + ".y", defaultY), screenH - h * s));
        bounds.put(key, new float[] {x, y, w * s, h * s});
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(s, s, 1);
    }

    private void panel(
            String key,
            String value,
            float defaultX,
            float defaultY,
            float screenW,
            float screenH) {
        float w = width(value) + 14, h = 21;
        place(key, w, h, defaultX, defaultY, screenW, screenH);
        if (flag(key, "bg")) rect(0, 0, w, h, 0xCE1E1E1E);
        if (flag(key, "bar")) rect(0, 0, 2, h, RED);
        text(value, 7, 3, colorOf(opt(key, "color")));
        GL11.glPopMatrix();
    }

    private boolean key(String binding, int fallback) {
        try {
            Object settings = field(mc, "field_71474_y", "gameSettings");
            Object k = field(settings, binding);
            return (Boolean) call(k, new String[] {"func_151470_d", "isKeyDown"});
        } catch (Exception e) {
            return Keyboard.isKeyDown(fallback);
        }
    }

    private void keycap(String label, float x, float y, float w, boolean pressed) {
        int active = colorOf(opt("keys", "active"));
        if (pressed) rect(x, y, w, 23, active);
        else if (flag("keys", "bg")) rect(x, y, w, 23, 0xCE1E1E1E);
        text(
                label,
                x + (w - width(label)) / 2,
                y + 4,
                pressed && active == COLORS[0] ? 0xFF1E1E1E : WHITE);
    }

    /** Friend notifications written by the launcher (antagon-notify-<millis><seq>.txt). */
    private final List<String[]> notices = new ArrayList<String[]>();

    private long noticeScan, noticeStart;

    private void pollNotices() {
        if (System.currentTimeMillis() - noticeScan < 500) return;
        noticeScan = System.currentTimeMillis();
        File[] files =
                new File(".").listFiles((dir, name) -> name.matches("antagon-notify-\\d+\\.txt"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : files) {
            try {
                String name = file.getName();
                long sent = Long.parseLong(name.substring(15, Math.min(name.length() - 4, 28)));
                String[] v =
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                                .split("\t", -1);
                if (v.length >= 3
                        && System.currentTimeMillis() - sent < 30000
                        && notices.size() < 5) {
                    for (int i = 0; i < v.length; i++) v[i] = unescapeField(v[i]);
                    notices.add(v);
                }
            } catch (Exception ignored) {
            }
            file.delete();
        }
    }

    /** Drawn in the HUD while playing and after the screen while a menu is open. */
    @SubscribeEvent
    public void hudNotices(RenderGameOverlayEvent.Post event) {
        try {
            if (event.type == RenderGameOverlayEvent.ElementType.ALL
                    && field(mc, "field_71462_r", "currentScreen") == null) drawNotices();
        } catch (Exception e) {
            report(e);
        }
    }

    @SubscribeEvent
    public void screenNotices(GuiScreenEvent.DrawScreenEvent.Post event) {
        drawNotices();
    }

    private void drawNotices() {
        pollNotices();
        if (notices.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (noticeStart == 0) noticeStart = now;
        long age = now - noticeStart;
        if (age > 4500) {
            notices.remove(0);
            noticeStart = 0;
            return;
        }
        boolean begun = false;
        try {
            String[] n = notices.get(0);
            int[] g = gui();
            float sw = (float) Math.ceil(g[0] / (double) g[2]), w = 210, h = 42;
            float slide = age < 200 ? (200 - age) / 200f : age > 4200 ? (age - 4200) / 300f : 0;
            float x = (sw - w) / 2, y = 6 - slide * (h + 10);
            begin(1);
            begun = true;
            rect(x + 3, y + 3, w, h, 0x60000000);
            rect(x, y, w, h, 0xF419191C);
            outline(x, y, w, h, 0xFF353539);
            rect(x, y, 2, h, RED);
            image(
                    n[0].equals("message") ? "chat.png" : "logo.png",
                    x + 10,
                    y + 13,
                    16,
                    16,
                    0,
                    0,
                    1,
                    1);
            text(fit(n[1], w - 44), x + 34, y + 5, WHITE);
            text(fit(n[2], w - 44), x + 34, y + 21, GRAY);
        } catch (Throwable e) {
            report(e);
        } finally {
            if (begun) end();
        }
    }

    private int[] gui() throws Exception {
        int dw = ((Number) field(mc, "field_71443_c", "displayWidth")).intValue(),
                dh = ((Number) field(mc, "field_71440_d", "displayHeight")).intValue();
        Object settings = field(mc, "field_71474_y", "gameSettings");
        int gui = ((Number) field(settings, "field_74335_Z", "guiScale")).intValue();
        if (gui == 0) gui = 1000;
        int factor = 1;
        while (factor < gui && dw / (factor + 1) >= 320 && dh / (factor + 1) >= 240) factor++;
        return new int[] {dw, dh, factor};
    }

    private void drawCrosshair(float cx, float cy, float scale) {
        int len = Integer.parseInt(opt("crosshair", "size")),
                t = Integer.parseInt(opt("crosshair", "thick")),
                gap = Integer.parseInt(opt("crosshair", "gap"));
        String style = opt("crosshair", "style");
        List<float[]> parts = new ArrayList<float[]>();
        float half = t / 2;
        if (!style.equals("ponto")) {
            parts.add(new float[] {-gap - len - half, -half, len, t});
            parts.add(new float[] {gap + t - half, -half, len, t});
            parts.add(new float[] {-half, -gap - len - half, t, len});
            parts.add(new float[] {-half, gap + t - half, t, len});
        }
        if (style.equals("ponto")) {
            float r = Math.max(1, len / 2f);
            for (int y = (int) -Math.ceil(r); y < Math.ceil(r); y++) {
                float row = y + .5f,
                        span = (float) Math.floor(Math.sqrt(Math.max(0, r * r - row * row)) + .5f);
                if (span > 0) parts.add(new float[] {-span, y, span * 2, 1});
            }
        } else if (!style.equals("cruz")) parts.add(new float[] {-half, -half, t, t});
        GL11.glPushMatrix();
        GL11.glTranslatef(cx, cy, 0);
        GL11.glScalef(scale, scale, 1);
        if (flag("crosshair", "outline"))
            for (float[] r : parts) rect(r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, 0xFF000000);
        for (float[] r : parts) rect(r[0], r[1], r[2], r[3], colorOf(opt("crosshair", "color")));
        GL11.glPopMatrix();
    }

    @SubscribeEvent
    public void crosshair(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS || !enabled("crosshair"))
            return;
        event.setCanceled(true);
        try {
            Object settings = field(mc, "field_71474_y", "gameSettings");
            if (((Number) field(settings, "field_74320_O", "thirdPersonView")).intValue() != 0
                    || (Boolean) field(settings, "field_74330_P", "showDebugInfo")) return;
            int[] g = gui();
            begin(1f / g[2]);
            try {
                drawCrosshair(g[0] / 2, g[1] / 2, 1);
            } finally {
                end();
            }
        } catch (Throwable e) {
            report(e);
        }
    }

    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL
                || hidden && !(menu != null && menu.editing)) return;
        try {
            Object player = field(mc, "field_71439_g", "thePlayer");
            if (player == null) return;
            int[] g = gui();
            float scale = hudScale = Math.max(.7f, Math.min(1.6f, number("scale", 1))),
                    sw = (float) Math.ceil(g[0] / (double) g[2]) / scale,
                    sh = (float) Math.ceil(g[1] / (double) g[2]) / scale;
            hudWidth = sw;
            hudHeight = sh;
            bounds.clear();
            try {
                begin(scale);
                if (enabled("fps")) {
                    Object fps =
                            invoke(minecraft, null, new String[] {"func_175610_ah", "getDebugFPS"});
                    panel("fps", fps + " FPS", 12, 12, sw, sh);
                }
                if (enabled("cps"))
                    panel(
                            "cps",
                            ModuleRegistry.cpsText(
                                    left.size(),
                                    right.size(),
                                    opt("cps", "buttons"),
                                    flag("cps", "suffix")),
                            12,
                            36,
                            sw,
                            sh);
                if (enabled("keys")) {
                    boolean mouse = flag("keys", "mouse"), space = flag("keys", "space");
                    float h = 48 + (mouse ? 25 : 0) + (space ? 25 : 0);
                    place("keys", 77, h, 12, 64, sw, sh);
                    float x = 0, y = 0;
                    keycap("W", x + 26, y, 25, key("field_74351_w", Keyboard.KEY_W));
                    keycap("A", x, y + 25, 25, key("field_74370_x", Keyboard.KEY_A));
                    keycap("S", x + 26, y + 25, 25, key("field_74368_y", Keyboard.KEY_S));
                    keycap("D", x + 52, y + 25, 25, key("field_74366_z", Keyboard.KEY_D));
                    float row = y + 50;
                    if (mouse) {
                        keycap("LMB", x, row, 38, Mouse.isButtonDown(0));
                        keycap("RMB", x + 39, row, 38, Mouse.isButtonDown(1));
                        row += 25;
                    }
                    if (space)
                        keycap("SPACE", x, row, 77, key("field_74314_A", Keyboard.KEY_SPACE));
                    GL11.glPopMatrix();
                }
                if (enabled("coords")) {
                    int x =
                            (int)
                                    Math.floor(
                                            ((Number) field(player, "field_70165_t", "posX"))
                                                    .doubleValue());
                    int y =
                            (int)
                                    Math.floor(
                                            ((Number) field(player, "field_70163_u", "posY"))
                                                    .doubleValue());
                    int z =
                            (int)
                                    Math.floor(
                                            ((Number) field(player, "field_70161_v", "posZ"))
                                                    .doubleValue());
                    panel("coords", "XYZ " + x + " " + y + " " + z, 12, 180, sw, sh);
                }
                if (enabled("ping")) {
                    String value = "LOCAL";
                    try {
                        Object net = call(mc, new String[] {"func_147114_u", "getNetHandler"});
                        Object id = call(player, new String[] {"func_110124_au", "getUniqueID"});
                        Object info =
                                call(net, new String[] {"func_175102_a", "getPlayerInfo"}, id);
                        if (info != null) {
                            int ping =
                                    ((Number)
                                                    call(
                                                            info,
                                                            new String[] {
                                                                "func_178853_c", "getResponseTime"
                                                            }))
                                            .intValue();
                            value = ping + " ms";
                        }
                    } catch (Exception ignored) {
                    }
                    panel("ping", value, 110, 12, sw, sh);
                }
                if (enabled("radio")) radioOverlay(sw, sh);
                if (enabled("armor")) armorOverlay(player, sw, sh);
                if (enabled("potions")) potionOverlay(player, sw, sh);
                if (enabled("combo"))
                    panel("combo", combo > 0 ? combo + " COMBO" : "SEM COMBO", 12, 252, sw, sh);
                if (enabled("reach"))
                    panel(
                            "reach",
                            (System.currentTimeMillis() - lastReachAt < 3000
                                            ? String.format(Locale.US, "%.2f", lastReach)
                                            : "--")
                                    + " BLOCOS",
                            12,
                            228,
                            sw,
                            sh);
                if (enabled("togglesprint")
                        && flag("togglesprint", "hud")
                        && (sprintToggled || menu != null && menu.editing))
                    panel("togglesprint", "SPRINT (TOGGLED)", 12, 204, sw, sh);
                if (enabled("clock")) panel("clock", clockText(), 210, 12, sw, sh);
            } finally {
                end();
            }
            if (enabled("scoreboard") && !flag("scoreboard", "hide")) drawScoreboard(sw, sh);
        } catch (Throwable error) {
            report(error);
        }
    }

    private void armorOverlay(Object player, float sw, float sh) throws Exception {
        List<StatusData.Armor> items =
                StatusData.armor(player, flag("armor", "held"), menu != null && menu.editing);
        if (items.isEmpty()) return;
        boolean horizontal = opt("armor", "layout").equals("horizontal");
        String mode = opt("armor", "durability");
        float cell = 38, content = 0;
        for (StatusData.Armor item : items)
            content = Math.max(content, width(item.text(mode)) * .7f);
        if (horizontal) cell = Math.max(cell, content + 8);
        float w = horizontal ? items.size() * cell + 8 : Math.max(40, 34 + content + 6);
        float h = horizontal ? (mode.equals("off") ? 27 : 40) : items.size() * 24 + 6;
        place("armor", w, h, sw - w - 12, sh - h - 45, sw, sh);
        try {
            if (flag("armor", "bg")) rect(0, 0, w, h, 0xCE1E1E1E);
            for (int i = 0; i < items.size(); i++) {
                StatusData.Armor item = items.get(i);
                float x = horizontal ? 4 + i * cell + (cell - 16) / 2 : 6;
                float y = horizontal ? 5 : 5 + i * 24;
                inventoryIcon(item.stack, x, y);
                String value = item.text(mode);
                int tint = item.maximum > 0 && item.remaining < item.maximum / 5f ? RED : WHITE;
                float tx = horizontal ? 4 + i * cell + (cell - width(value) * .7f) / 2 : 31;
                float ty = horizontal ? 24 : y + 2;
                GL11.glPushMatrix();
                GL11.glTranslatef(tx, ty, 0);
                GL11.glScalef(.7f, .7f, 1);
                text(value, 0, 0, tint);
                GL11.glPopMatrix();
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    private void inventoryIcon(Object stack, float x, float y) throws Exception {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        Class<?> state = Class.forName("net.minecraft.client.renderer.GlStateManager");
        Class<?> helper = Class.forName("net.minecraft.client.renderer.RenderHelper");
        try {
            // PixelFont binds GL textures directly. Invalidate Minecraft's cached binding
            // before RenderItem changes atlas filters, or it can modify the font texture.
            invoke(state, null, new String[] {"func_179144_i", "bindTexture"}, 0);
            invoke(state, null, new String[] {"func_179117_G", "resetColor"});
            invoke(state, null, new String[] {"func_179131_c", "color"}, 1f, 1f, 1f, 1f);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(0x803A); // GL12.GL_RESCALE_NORMAL
            invoke(helper, null, new String[] {"func_74520_c", "enableGUIStandardItemLighting"});
            Object renderer = call(mc, new String[] {"func_175599_af", "getRenderItem"});
            call(
                    renderer,
                    new String[] {"func_180450_b", "renderItemAndEffectIntoGUI"},
                    stack,
                    0,
                    0);
        } finally {
            invoke(helper, null, new String[] {"func_74518_a", "disableStandardItemLighting"});
            invoke(state, null, new String[] {"func_179101_C", "disableRescaleNormal"});
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glPopMatrix();
        }
    }

    private void potionOverlay(Object player, float sw, float sh) throws Exception {
        List<StatusData.Potion> effects = StatusData.potions(player, menu != null && menu.editing);
        if (effects.isEmpty()) return;
        boolean names = flag("potions", "names");
        float w = 75;
        for (StatusData.Potion effect : effects)
            if (names) w = Math.max(w, width(effect.name) * .75f + 39);
        w = Math.min(w, 205);
        float h = effects.size() * 30 + 6;
        place("potions", w, h, sw - w - 12, 62, sw, sh);
        try {
            if (flag("potions", "bg")) rect(0, 0, w, h, 0xCE1E1E1E);
            for (int i = 0; i < effects.size(); i++) {
                StatusData.Potion effect = effects.get(i);
                float y = 6 + i * 30;
                if (effect.icon >= 0) potionIcon(effect.icon, 6, y + 2);
                int tint =
                        flag("potions", "blink")
                                        && effect.duration < 200
                                        && System.currentTimeMillis() / 500 % 2 == 0
                                ? RED
                                : WHITE;
                GL11.glPushMatrix();
                GL11.glTranslatef(31, y, 0);
                GL11.glScalef(.75f, .75f, 1);
                if (names) text(fit(effect.name, (w - 37) / .75f), 0, 0, WHITE);
                text(StatusData.duration(effect.duration), 0, names ? 15 : 6, tint);
                GL11.glPopMatrix();
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    private Object potionTexture;

    private void potionIcon(int icon, float x, float y) throws Exception {
        if (potionTexture == null)
            potionTexture =
                    Class.forName("net.minecraft.util.ResourceLocation")
                            .getConstructor(String.class)
                            .newInstance("textures/gui/container/inventory.png");
        Object textures = call(mc, new String[] {"func_110434_K", "getTextureManager"});
        // Bind directly so the custom font's texture binding cannot invalidate Minecraft's cache.
        Object texture =
                call(textures, new String[] {"func_110581_b", "getTexture"}, potionTexture);
        if (texture == null) {
            call(textures, new String[] {"func_110577_a", "bindTexture"}, potionTexture);
            texture = call(textures, new String[] {"func_110581_b", "getTexture"}, potionTexture);
        }
        int id =
                ((Number) call(texture, new String[] {"func_110552_b", "getGlTextureId"}))
                        .intValue();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        color(WHITE);
        float u = (icon % 8 * 18) / 256f, v = (198 + icon / 8 * 18) / 256f, size = 18 / 256f;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u, v);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u + size, v);
        GL11.glVertex2f(x + 18, y);
        GL11.glTexCoord2f(u + size, v + size);
        GL11.glVertex2f(x + 18, y + 18);
        GL11.glTexCoord2f(u, v + size);
        GL11.glVertex2f(x, y + 18);
        GL11.glEnd();
    }

    private String clockText() {
        long now = System.currentTimeMillis(), minute = now / 60000;
        String format = opt("clock", "format");
        if (minute != clockMinute || !format.equals(clockFormat)) {
            clockMinute = minute;
            clockFormat = format;
            clockText = (format.equals("12h") ? clock12 : clock24).format(new Date(now));
        }
        return clockText;
    }

    private void drawScoreboard(float sw, float sh) throws Exception {
        Object world = field(mc, "field_71441_e", "theWorld");
        Object player = field(mc, "field_71439_g", "thePlayer");
        if (world == null || player == null) return;
        Object board = call(world, new String[] {"func_96441_U", "getScoreboard"});
        Object objective = null;
        Object team =
                call(
                        board,
                        new String[] {"func_96509_i", "getPlayersTeam"},
                        call(player, new String[] {"func_70005_c_", "getName"}));
        if (team != null) {
            int color =
                    ((Number)
                                    call(
                                            call(
                                                    team,
                                                    new String[] {
                                                        "func_178775_l", "getChatFormat"
                                                    }),
                                            new String[] {"func_175746_b", "getColorIndex"}))
                            .intValue();
            if (color >= 0)
                objective =
                        call(
                                board,
                                new String[] {"func_96539_a", "getObjectiveInDisplaySlot"},
                                3 + color);
        }
        if (objective == null)
            objective = call(board, new String[] {"func_96539_a", "getObjectiveInDisplaySlot"}, 1);
        if (objective == null) return;
        Class<?> teams = Class.forName("net.minecraft.scoreboard.ScorePlayerTeam");
        List<String[]> lines = new ArrayList<String[]>();
        for (Object score :
                (Collection<?>)
                        call(board, new String[] {"func_96534_i", "getSortedScores"}, objective)) {
            String name = (String) call(score, new String[] {"func_96653_e", "getPlayerName"});
            if (name == null || name.startsWith("#")) continue;
            Object owner = call(board, new String[] {"func_96509_i", "getPlayersTeam"}, name);
            String text =
                    (String)
                            invoke(
                                    teams,
                                    null,
                                    new String[] {"func_96667_a", "formatPlayerName"},
                                    owner,
                                    name);
            lines.add(
                    new String[] {
                        text,
                        "\u00a7c" + call(score, new String[] {"func_96652_c", "getScorePoints"})
                    });
        }
        if (lines.size() > 15) lines = lines.subList(lines.size() - 15, lines.size());
        Collections.reverse(lines);
        Object font = field(mc, "field_71466_p", "fontRendererObj");
        String title = (String) call(objective, new String[] {"func_96678_d", "getDisplayName"});
        boolean numbers = flag("scoreboard", "numbers"), background = flag("scoreboard", "bg");
        int width = stringWidth(font, title);
        for (String[] line : lines)
            width =
                    Math.max(
                            width,
                            stringWidth(font, line[0])
                                    + (numbers ? 3 + stringWidth(font, line[1]) : 0));
        float s = percent("scoreboard", "size"), w = width + 4, h = (lines.size() + 1) * 9 + 1;
        float x = Math.max(0, Math.min(number("scoreboard.x", sw - w * s - 1), sw - w * s));
        float y = Math.max(0, Math.min(number("scoreboard.y", sh / 2 - h * s / 3), sh - h * s));
        bounds.put("scoreboard", new float[] {x, y, w * s, h * s});
        GL11.glPushMatrix();
        GL11.glScalef(hudScale, hudScale, 1);
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(s, s, 1);
        if (background) {
            Class<?> gui = Class.forName("net.minecraft.client.gui.Gui");
            String[] drawRect = {"func_73734_a", "drawRect"};
            invoke(gui, null, drawRect, 0, 0, (int) w, 10, 0x66000000);
            invoke(gui, null, drawRect, 0, 10, (int) w, (int) h, 0x50000000);
        }
        drawString(font, title, (int) (w - stringWidth(font, title)) / 2, 1, 0xFFFFFFFF);
        for (int i = 0; i < lines.size(); i++) {
            drawString(font, lines.get(i)[0], 2, 11 + i * 9, 0xFFFFFFFF);
            if (numbers)
                drawString(
                        font,
                        lines.get(i)[1],
                        (int) w - 2 - stringWidth(font, lines.get(i)[1]),
                        11 + i * 9,
                        0xFFFFFFFF);
        }
        GL11.glPopMatrix();
    }

    private static int stringWidth(Object font, String text) throws Exception {
        return ((Number) call(font, new String[] {"func_78256_a", "getStringWidth"}, text))
                .intValue();
    }

    private static void drawString(Object font, String text, int x, int y, int color)
            throws Exception {
        invoke(
                font.getClass(),
                font,
                new String[] {"func_78276_b", "drawString"},
                text,
                x,
                y,
                color);
    }

    private final class Menu extends GuiScreen {
        static final int PW = MenuLayout.WIDTH;
        private final GuiScreen parent;
        int category = 0;
        int[] visibleMods = ModuleRegistry.filter(0);
        float scroll = 0, scrollTarget = 0;
        long lastDraw = 0;
        String page = null, drag = null, resizing = null, notice = "";
        float resizeAnchorX, resizeAnchorY, resizeWidth, resizeHeight, resizeSize;
        int corner;
        String binding = null, typing = null;
        boolean volumeDrag = false;
        long volumeSent = 0;
        float ms = 1;
        boolean editing = false;
        float x0, y0, dx, dy;

        Menu(GuiScreen parent) {
            this.parent = parent;
            menu = this;
        }

        private boolean in(int mx, int my, float x, float y, float w, float h) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }

        private void button(
                String label, float x, float y, float w, float h, int mx, int my, boolean primary) {
            boolean hover = in(mx, my, x, y, w, h);
            rect(
                    x,
                    y,
                    w,
                    h,
                    primary ? (hover ? 0xFFFF3A3A : RED) : (hover ? 0xFF3C3C3C : 0xFF2C2C2C));
            text(label, x + (w - width(label)) / 2, y + h / 2 - 7.5f, WHITE);
        }

        void close() {
            try {
                call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, parent);
            } catch (Exception e) {
                report(e);
            }
        }

        @Override
        public boolean func_73868_f() {
            return false;
        }

        @Override
        public void func_146281_b() {
            if (menu == this) menu = null;
            save();
        }

        @Override
        protected void func_73869_a(char c, int key) {
            if (typing != null) {
                String property = "autotext." + typing + ".text",
                        text = config.getProperty(property, "");
                boolean paste =
                        key == Keyboard.KEY_V
                                && (Keyboard.isKeyDown(Keyboard.KEY_LMETA)
                                        || Keyboard.isKeyDown(Keyboard.KEY_RMETA)
                                        || Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                                        || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_ESCAPE) typing = null;
                else if (key == Keyboard.KEY_BACK) {
                    if (!text.isEmpty())
                        config.setProperty(property, text.substring(0, text.length() - 1));
                } else if (paste)
                    config.setProperty(property, chatText(text + Platform.clipboard()));
                else if (c >= 32 && c != 127 && c != 167)
                    config.setProperty(property, chatText(text + c));
                if (typing == null) save();
                return;
            }
            if (binding != null) {
                if (key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE)
                    config.setProperty(binding, "NENHUMA");
                else if (key != Keyboard.KEY_ESCAPE
                        && key != Keyboard.KEY_RSHIFT
                        && key != Keyboard.KEY_NONE)
                    config.setProperty(binding, Keyboard.getKeyName(key));
                binding = null;
                save();
                return;
            }
            if (page == null && !editing) {
                if (key == Keyboard.KEY_DOWN || key == Keyboard.KEY_NEXT) {
                    scrollBy(key == Keyboard.KEY_NEXT ? MenuLayout.viewport(ph()) : 35);
                    return;
                }
                if (key == Keyboard.KEY_UP || key == Keyboard.KEY_PRIOR) {
                    scrollBy(key == Keyboard.KEY_PRIOR ? -MenuLayout.viewport(ph()) : -35);
                    return;
                }
                if (key == Keyboard.KEY_HOME) {
                    scrollTarget = 0;
                    return;
                }
                if (key == Keyboard.KEY_END) {
                    scrollTarget = MenuLayout.maxScroll(visibleMods.length, ph());
                    return;
                }
            }
            if (key == Keyboard.KEY_RSHIFT) close();
            else if (key == Keyboard.KEY_ESCAPE) {
                if (editing) editing = false;
                else if (page != null) page = null;
                else close();
            }
        }

        @Override
        public void func_73863_a(int mx, int my, float partial) {
            try {
                ms =
                        editing
                                ? 1
                                : MenuLayout.scale(
                                        field_146294_l, field_146295_m, page == null ? 0 : ph());
                begin(ms);
                int w = (int) (field_146294_l / ms), h = (int) (field_146295_m / ms);
                mx = (int) (mx / ms);
                my = (int) (my / ms);
                if (editing) {
                    drawEdit(mx, my, w, h);
                    return;
                }
                rect(0, 0, w, h, 0x99000000);
                x0 = (w - PW) / 2;
                y0 = Math.max(4, (h - ph()) / 2);
                rect(x0 + 4, y0 + 5, PW, ph(), 0x60000000);
                rect(x0, y0, PW, ph(), 0xFA19191C);
                outline(x0, y0, PW, ph(), 0xFF353539);
                if (page != null) {
                    rect(x0 + 1, y0 + 1, PW - 2, 24, 0xFF222226);
                    rect(x0, y0, 3, 24, RED);
                }
                if (page == null) drawHome(mx, my);
                else drawOptions(mx, my);
                if (!notice.isEmpty())
                    text(notice, x0 + (PW - width(notice)) / 2, y0 + ph() - 16, RED);
            } catch (Throwable e) {
                report(e);
            } finally {
                end();
            }
        }

        static final float SLIDER = 150;
        String rangeDrag = null;
        static final float GRID = 8, SNAP = 4, GAP = 2;
        float guideX = -1, guideY = -1;

        private float top() {
            return y0 + 32 + (page != null && page.equals("crosshair") ? 72 : 0);
        }

        private float sliderX() {
            return x0 + PW - 200;
        }

        private void slider(float y, float t) {
            float sx = sliderX();
            rect(sx, y + 10, SLIDER, 2, 0xFF4A4A4A);
            rect(sx, y + 10, SLIDER * t, 2, RED);
            rect(sx + SLIDER * t - 3, y + 6, 6, 10, WHITE);
        }

        private float[] range(String[] o) {
            String[] r = o[2].split(":", -1);
            return new float[] {
                Float.parseFloat(r[1]), Float.parseFloat(r[2]), Float.parseFloat(r[3])
            };
        }

        private void setRange(String[] o, float mx) {
            float[] r = range(o);
            float t = Math.max(0, Math.min(1, (mx - sliderX()) / SLIDER));
            float v = r[0] + Math.round(t * (r[1] - r[0]) / r[2]) * r[2];
            String unit = o[2].split(":", -1)[4];
            config.setProperty(page + "." + o[0], (v == (int) v ? "" + (int) v : "" + v) + unit);
        }

        private void drawCrosshairPreview() {
            float x = x0 + 8, y = y0 + 32, w = PW - 16;
            rect(x, y, w, 34, 0xFF6E9BD3);
            rect(x, y + 34, w, 30, 0xFF4F7A33);
            try {
                float px = 1 / (gui()[2] * ms);
                drawCrosshair(x + w * .3f, y + 32, px);
                drawCrosshair(x + w * .7f, y + 32, px * 4);
                text("REAL", x + 6, y + 3, WHITE);
                text("ZOOM 4X", x + w - 6 - width("ZOOM 4X"), y + 3, WHITE);
            } catch (Exception e) {
                report(e);
            }
        }

        private int ph() {
            return page == null
                    ? MenuLayout.homeHeight(field_146294_l, field_146295_m)
                    : Math.max(160, (int) (top() - y0) + 8 + options(page).length * 26);
        }

        private float[] card(int i) {
            return MenuLayout.card(i, x0, y0, scroll);
        }

        private void toggle(float x, float y, boolean on) {
            rect(x, y, 22, 10, on ? RED : 0xFF4A4A4A);
            rect(on ? x + 13 : x + 1, y + 1, 8, 8, WHITE);
        }

        private void slidersIcon(float x, float y, int c) {
            rect(x, y + 1, 10, 1, c);
            rect(x, y + 5, 10, 1, c);
            rect(x, y + 9, 10, 1, c);
            rect(x + 6, y, 2, 3, c);
            rect(x + 2, y + 4, 2, 3, c);
            rect(x + 5, y + 8, 2, 3, c);
        }

        private void setCategory(int next) {
            category = next;
            visibleMods = ModuleRegistry.filter(category);
            scroll = scrollTarget = 0;
        }

        private void scrollBy(float amount) {
            scrollTarget = MenuLayout.clampScroll(scrollTarget + amount, visibleMods.length, ph());
        }

        private void mark(float x, float y) {
            rect(x, y, 26, 26, RED);
            rect(x + 11, y + 4, 4, 18, WHITE);
            rect(x + 4, y + 11, 18, 4, WHITE);
            for (int i = 0; i < 4; i++) {
                rect(x + 6 + i * 3, y + 6 + i * 3, 3, 3, WHITE);
                rect(x + 15 - i * 3, y + 6 + i * 3, 3, 3, WHITE);
            }
        }

        private void drawHome(int mx, int my) throws Exception {
            long now = System.nanoTime();
            float delta = lastDraw == 0 ? 1 / 60f : Math.min(.05f, (now - lastDraw) / 1e9f);
            lastDraw = now;
            scrollTarget = MenuLayout.clampScroll(scrollTarget, visibleMods.length, ph());
            scroll += (scrollTarget - scroll) * (1 - (float) Math.exp(-delta * 18));
            if (Math.abs(scroll - scrollTarget) < .05f) scroll = scrollTarget;
            scroll = MenuLayout.clampScroll(scroll, visibleMods.length, ph());
            mark(x0 + 16, y0 + 14);
            text("ANTAGON", x0 + 52, y0 + 20, WHITE);
            button("EDITAR HUD", x0 + PW - 126, y0 + 16, 92, 24, mx, my, true);
            button("X", x0 + PW - 28, y0 + 16, 16, 24, mx, my, false);
            rect(x0 + 16, y0 + 49, PW - 32, 1, 0xFF303035);
            for (int i = 0; i < CATEGORIES.length; i++) {
                float x = x0 + 16 + i * 91;
                boolean selected = category == i;
                rect(
                        x,
                        y0 + 56,
                        85,
                        20,
                        selected
                                ? 0xFF442023
                                : in(mx, my, x, y0 + 56, 85, 20) ? 0xFF2B2B30 : 0xFF202024);
                text(
                        CATEGORIES[i],
                        x + (85 - width(CATEGORIES[i])) / 2,
                        y0 + 58,
                        selected ? WHITE : GRAY);
                if (selected) rect(x, y0 + 74, 85, 2, RED);
            }
            String count = visibleMods.length + " MODS";
            text(count, x0 + PW - 16 - width(count), y0 + 58, GRAY);
            float top = y0 + MenuLayout.CONTENT_TOP, viewport = MenuLayout.viewport(ph());
            int[] dimensions = gui();
            float pixels = dimensions[2] * ms;
            GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(
                    (int) ((x0 + 12) * pixels),
                    (int) (dimensions[1] - (top + viewport) * pixels),
                    (int) ((PW - 24) * pixels),
                    (int) (viewport * pixels));
            try {
                for (int slot = 0; slot < visibleMods.length; slot++) {
                    int index = visibleMods[slot];
                    float[] c = card(slot);
                    if (c[1] + c[3] <= top || c[1] >= top + viewport) continue;
                    String mod = MODS[index];
                    boolean on = enabled(mod),
                            hover =
                                    in(
                                            mx,
                                            my,
                                            c[0],
                                            Math.max(top, c[1]),
                                            c[2],
                                            Math.min(top + viewport, c[1] + c[3])
                                                    - Math.max(top, c[1]));
                    rect(c[0], c[1], c[2], c[3], hover ? 0xFF303035 : 0xFF242428);
                    outline(
                            c[0],
                            c[1],
                            c[2],
                            c[3],
                            hover ? 0xFF636067 : on ? 0xFF503035 : 0xFF343439);
                    rect(c[0] + 1, c[1] + 1, on ? 24 : 8, 2, on ? RED : 0xFF555259);
                    text(NAMES[index], c[0] + 10, c[1] + 8, WHITE);
                    text(fit(DESCRIPTIONS[index], c[2] - 20), c[0] + 10, c[1] + 23, GRAY);
                    rect(c[0] + 10, c[1] + 46, 3, 3, on ? RED : 0xFF6A686D);
                    text(on ? "ATIVO" : "DESLIGADO", c[0] + 18, c[1] + 40, on ? WHITE : GRAY);
                    if (options(mod).length > 0) {
                        float gx = c[0] + c[2] - 56;
                        boolean over = in(mx, my, gx - 4, c[1] + 38, 20, 20);
                        if (over) rect(gx - 4, c[1] + 38, 20, 20, 0xFF444149);
                        slidersIcon(gx + 1, c[1] + 43, over ? WHITE : GRAY);
                    }
                    toggle(c[0] + c[2] - 32, c[1] + 43, on);
                }
            } finally {
                GL11.glPopAttrib();
            }
            float max = MenuLayout.maxScroll(visibleMods.length, ph());
            if (max > 0) {
                float thumb = Math.max(24, viewport * viewport / (viewport + max));
                rect(x0 + PW - 8, top, 2, viewport, 0xFF303035);
                rect(x0 + PW - 8, top + scroll / max * (viewport - thumb), 2, thumb, RED);
            }
            int enabledCount = 0;
            for (String mod : MODS) if (enabled(mod)) enabledCount++;
            rect(x0 + 16, y0 + ph() - 22, PW - 32, 1, 0xFF303035);
            text(enabledCount + " ATIVOS", x0 + 16, y0 + ph() - 18, GRAY);
        }

        private void drawOptions(int mx, int my) {
            button("<", x0 + 8, y0 + 4, 18, 16, mx, my, false);
            text(NAMES[Arrays.asList(MODS).indexOf(page)], x0 + 34, y0 + 5, WHITE);
            if (page.equals("crosshair")) drawCrosshairPreview();
            String[][] opts = options(page);
            for (int k = 0; k < opts.length; k++) {
                float ry = top() + k * 26;
                String v = opt(page, opts[k][0]);
                rect(x0 + 8, ry, PW - 16, 22, 0xFF262626);
                text(opts[k][1], x0 + 16, ry + 3, WHITE);
                if (opts[k][0].equals("library")) {
                    button("ABRIR RÁDIO", x0 + PW - 108, ry + 3, 92, 16, mx, my, true);
                } else if (opts[k][0].equals("controls")) {
                    button("<<", x0 + PW - 108, ry + 3, 24, 16, mx, my, false);
                    button(
                            radioState.equals("playing") ? "PAUSAR" : "TOCAR",
                            x0 + PW - 82,
                            ry + 3,
                            40,
                            16,
                            mx,
                            my,
                            false);
                    button(">>", x0 + PW - 40, ry + 3, 24, 16, mx, my, false);
                } else if (opts[k][2].equals("autotext")) {
                    String slot = opts[k][0],
                            text = config.getProperty("autotext." + slot + ".text", "");
                    String key = config.getProperty("autotext." + slot + ".key", "NENHUMA");
                    boolean active = slot.equals(typing),
                            waiting = ("autotext." + slot + ".key").equals(binding);
                    float fw = PW - 136;
                    rect(x0 + 14, ry + 3, fw, 16, active ? 0xFF3A1414 : 0xFF1A1A1A);
                    if (active) outline(x0 + 14, ry + 3, fw, 16, RED);
                    String shown = text.isEmpty() && !active ? "/comando ou mensagem" : text;
                    shown =
                            fit(
                                    shown
                                            + (active && System.currentTimeMillis() / 500 % 2 == 0
                                                    ? "_"
                                                    : ""),
                                    fw - 10);
                    text(shown, x0 + 19, ry + 3, text.isEmpty() && !active ? GRAY : WHITE);
                    button(
                            waiting ? "APERTE..." : key.replace("MOUSE", "MOUSE "),
                            x0 + PW - 108,
                            ry + 3,
                            92,
                            16,
                            mx,
                            my,
                            waiting);
                } else if (opts[k][2].equals("key")) {
                    boolean waiting = (page + "." + opts[k][0]).equals(binding);
                    button(
                            waiting ? "APERTE..." : v.replace("MOUSE", "MOUSE "),
                            x0 + PW - 108,
                            ry + 3,
                            92,
                            16,
                            mx,
                            my,
                            waiting);
                } else if (opts[k][2].startsWith("range:")) {
                    float[] r = range(opts[k]);
                    slider(ry, (Platform.number(v.replace("%", "")) - r[0]) / (r[1] - r[0]));
                    text(v, x0 + PW - 16 - width(v), ry + 3, WHITE);
                } else if (opts[k][2].equals("slider")) {
                    int vol = radioVolume;
                    if (vol >= 0) slider(ry, vol / 100f);
                    else rect(sliderX(), ry + 10, SLIDER, 2, 0xFF4A4A4A);
                    String label = vol < 0 ? "--" : "" + vol;
                    text(label, x0 + PW - 16 - width(label), ry + 3, vol < 0 ? GRAY : WHITE);
                } else if (v.equals("on") || v.equals("off"))
                    toggle(x0 + PW - 38, ry + 6, v.equals("on"));
                else {
                    button(v.toUpperCase(), x0 + PW - 108, ry + 3, 92, 16, mx, my, false);
                    if (Arrays.asList(COLOR_NAMES).contains(v))
                        rect(x0 + PW - 102, ry + 7, 8, 8, colorOf(v));
                }
            }
        }

        private void drawEdit(int mx, int my, int w, int h) {
            rect(0, 0, w, h, 0x33000000);
            float s = hudScale;
            if (drag != null) {
                for (float gx = 0; gx < w; gx += GRID * s) rect(gx, 0, 1, h, 0x30FFFFFF);
                for (float gy = 0; gy < h; gy += GRID * s) rect(0, gy, w, 1, 0x30FFFFFF);
                if (guideX >= 0) rect(guideX * s, 0, 1, h, 0xCCEE1515);
                if (guideY >= 0) rect(0, guideY * s, w, 1, 0xCCEE1515);
            }
            for (Map.Entry<String, float[]> e : bounds.entrySet()) {
                float[] b = e.getValue();
                boolean active = e.getKey().equals(drag) || e.getKey().equals(resizing);
                outline(
                        b[0] * s - 1,
                        b[1] * s - 1,
                        b[2] * s + 2,
                        b[3] * s + 2,
                        active ? RED : 0xCCF0EEE8);
                for (int c = 0; c < 4; c++) {
                    float[] p = cornerOf(b, c);
                    rect(
                            p[0] - 3,
                            p[1] - 3,
                            6,
                            6,
                            in(mx, my, p[0] - 4, p[1] - 4, 8, 8)
                                            || e.getKey().equals(resizing) && c == corner
                                    ? RED
                                    : WHITE);
                }
                if (e.getKey().equals(resizing)) {
                    String pct = Math.round(percent(resizing, "size") * 100) + "%";
                    float[] p = cornerOf(b, corner);
                    text(pct, p[0] + 5, p[1] - 4, WHITE);
                }
            }
            float bx = w / 2f - 112, by = h - 28;
            rect(bx - 4, by - 4, 232, 26, 0xF21E1E1E);
            button("-", bx, by, 18, 18, mx, my, false);
            String pct = Math.round(hudScale * 100) + "%";
            text(pct, bx + 39 - width(pct) / 2, by + 2, WHITE);
            button("+", bx + 60, by, 18, 18, mx, my, false);
            button("REDEFINIR", bx + 84, by, 70, 18, mx, my, false);
            button("PRONTO", bx + 158, by, 66, 18, mx, my, true);
        }

        @Override
        public void func_146274_d() throws IOException {
            super.func_146274_d();
            int wheel = Mouse.getEventDWheel();
            if (wheel == 0) return;
            if (!editing) {
                if (page == null) scrollBy(wheel > 0 ? -35 : 35);
                return;
            }
            try {
                int[] g = gui();
                float mx = Mouse.getEventX() * field_146294_l / (float) g[0] / hudScale,
                        my =
                                (field_146295_m
                                                - Mouse.getEventY() * field_146295_m / (float) g[1]
                                                - 1)
                                        / hudScale;
                for (Map.Entry<String, float[]> e : bounds.entrySet()) {
                    float[] b = e.getValue();
                    if (mx >= b[0] && my >= b[1] && mx < b[0] + b[2] && my < b[1] + b[3]) {
                        setSize(e.getKey(), percent(e.getKey(), "size") + (wheel > 0 ? .1f : -.1f));
                        save();
                        return;
                    }
                }
            } catch (Exception e) {
                report(e);
            }
        }

        private void setVolume(float mx, boolean last) {
            int v = Math.max(0, Math.min(100, Math.round((mx - sliderX()) / SLIDER * 100)));
            radioVolume = v;
            volumeSentAt = System.currentTimeMillis();
            if (last || System.currentTimeMillis() - volumeSent > 150) {
                volumeSent = System.currentTimeMillis();
                Radio.command("volume", Integer.toString(v));
            }
        }

        private void cycle(String mod, String[] o) {
            List<String> values = Arrays.asList(o[2].split(","));
            config.setProperty(
                    mod + "." + o[0],
                    values.get((values.indexOf(opt(mod, o[0])) + 1) % values.size()));
            save();
        }

        private void setScale(float v) {
            config.setProperty(
                    "scale", "" + Math.round(Math.max(.7f, Math.min(1.6f, v)) * 10) / 10f);
            save();
        }

        @Override
        protected void func_73864_a(int mx, int my, int button) {
            if (typing != null) {
                typing = null;
                save();
            }
            if (binding != null) {
                if (button >= 2) config.setProperty(binding, "MOUSE" + (button + 1));
                binding = null;
                save();
                return;
            }
            if (button != 0) return;
            if (!editing) {
                mx = (int) (mx / ms);
                my = (int) (my / ms);
            }
            if (editing) {
                float bx = field_146294_l / 2f - 112, by = field_146295_m - 28;
                if (in(mx, my, bx, by, 18, 18)) {
                    setScale(hudScale - .1f);
                    return;
                }
                if (in(mx, my, bx + 60, by, 18, 18)) {
                    setScale(hudScale + .1f);
                    return;
                }
                if (in(mx, my, bx + 84, by, 70, 18)) {
                    for (String m : MODS) {
                        config.remove(m + ".x");
                        config.remove(m + ".y");
                    }
                    config.setProperty("scale", "1");
                    save();
                    return;
                }
                if (in(mx, my, bx + 158, by, 66, 18)) {
                    editing = false;
                    return;
                }
                for (Map.Entry<String, float[]> e : bounds.entrySet()) {
                    float[] b = e.getValue();
                    for (int c = 0; c < 4; c++) {
                        float[] p = cornerOf(b, c);
                        if (in(mx, my, p[0] - 4, p[1] - 4, 8, 8)) {
                            float[] a = cornerOf(b, 3 - c);
                            resizing = e.getKey();
                            corner = c;
                            resizeAnchorX = a[0];
                            resizeAnchorY = a[1];
                            resizeWidth = b[2] * hudScale;
                            resizeHeight = b[3] * hudScale;
                            resizeSize = percent(resizing, "size");
                            return;
                        }
                    }
                }
                for (Map.Entry<String, float[]> e : bounds.entrySet()) {
                    float[] b = e.getValue();
                    float hx = mx / hudScale, hy = my / hudScale;
                    if (hx >= b[0] && hy >= b[1] && hx < b[0] + b[2] && hy < b[1] + b[3]) {
                        drag = e.getKey();
                        dx = hx - b[0];
                        dy = hy - b[1];
                        return;
                    }
                }
                return;
            }
            if (page == null) {
                if (in(mx, my, x0 + PW - 126, y0 + 16, 92, 24)) {
                    editing = true;
                    return;
                }
                if (in(mx, my, x0 + PW - 28, y0 + 16, 16, 24)) {
                    close();
                    return;
                }
                for (int i = 0; i < CATEGORIES.length; i++)
                    if (in(mx, my, x0 + 16 + i * 91, y0 + 56, 85, 20)) {
                        setCategory(i);
                        return;
                    }
                if (!in(
                        mx,
                        my,
                        x0 + 12,
                        y0 + MenuLayout.CONTENT_TOP,
                        PW - 24,
                        MenuLayout.viewport(ph()))) return;
                for (int slot = 0; slot < visibleMods.length; slot++) {
                    int i = visibleMods[slot];
                    float[] c = card(slot);
                    if (!in(mx, my, c[0], c[1], c[2], c[3])) continue;
                    if (options(MODS[i]).length > 0
                            && in(mx, my, c[0] + c[2] - 60, c[1] + 38, 20, 20)) {
                        page = MODS[i];
                        return;
                    }
                    config.setProperty(MODS[i], "" + !enabled(MODS[i]));
                    save();
                    if (MODS[i].equals("radio")) {
                        if (enabled("radio")) radioPlay();
                        else Radio.command("stop");
                    }
                    return;
                }
            } else {
                if (in(mx, my, x0 + 8, y0 + 4, 18, 16)) {
                    page = null;
                    return;
                }
                String[][] opts = options(page);
                for (int k = 0; k < opts.length; k++) {
                    float ry = top() + k * 26;
                    if (opts[k][0].equals("library")) {
                        if (in(mx, my, x0 + PW - 108, ry + 3, 92, 16)) radioPlay();
                        continue;
                    }
                    if (opts[k][2].equals("autotext")) {
                        if (in(mx, my, x0 + 14, ry + 3, PW - 136, 16)) typing = opts[k][0];
                        else if (in(mx, my, x0 + PW - 108, ry + 3, 92, 16))
                            binding = "autotext." + opts[k][0] + ".key";
                        continue;
                    }
                    if (opts[k][2].equals("key")) {
                        if (in(mx, my, x0 + PW - 108, ry + 3, 92, 16))
                            binding = page + "." + opts[k][0];
                        continue;
                    }
                    if (opts[k][2].startsWith("range:")) {
                        if (in(mx, my, sliderX() - 6, ry, SLIDER + 12, 22)) {
                            rangeDrag = opts[k][0];
                            setRange(opts[k], mx);
                        }
                        continue;
                    }
                    if (opts[k][2].equals("slider")) {
                        if (in(mx, my, sliderX() - 6, ry, SLIDER + 12, 22)) {
                            volumeDrag = true;
                            setVolume(mx, false);
                        }
                        continue;
                    }
                    if (opts[k][0].equals("controls")) {
                        if (in(mx, my, x0 + PW - 108, ry + 3, 24, 16)) Radio.command("previous");
                        else if (in(mx, my, x0 + PW - 82, ry + 3, 40, 16)) radioToggle();
                        else if (in(mx, my, x0 + PW - 40, ry + 3, 24, 16)) Radio.command("next");
                        continue;
                    }
                    if (in(mx, my, x0 + PW - 108, ry, 92, 22)) {
                        cycle(page, opts[k]);
                        return;
                    }
                }
            }
        }

        private String chatText(String text) {
            String clean = text.replaceAll("[\\p{Cntrl}\u00a7]", "");
            return clean.length() > 100 ? clean.substring(0, 100) : clean;
        }

        private float setSize(String key, float size) {
            int pct = Math.round(Math.max(.5f, Math.min(2f, size)) * 20) * 5;
            config.setProperty(key + ".size", pct + "%");
            return pct / 100f;
        }

        private float[] cornerOf(float[] b, int c) {
            float s = hudScale;
            return new float[] {
                (c % 2 == 0 ? b[0] : b[0] + b[2]) * s, (c < 2 ? b[1] : b[1] + b[3]) * s
            };
        }

        @Override
        protected void func_146273_a(int mx, int my, int button, long time) {
            if (!editing) {
                mx = (int) (mx / ms);
                my = (int) (my / ms);
                if (volumeDrag) setVolume(mx, false);
                if (rangeDrag != null)
                    for (String[] o : options(page)) if (o[0].equals(rangeDrag)) setRange(o, mx);
                return;
            }
            if (resizing != null) {
                float wide = corner % 2 == 1 ? mx - resizeAnchorX : resizeAnchorX - mx;
                float
                        f =
                                setSize(resizing, resizeSize * Math.max(4, wide) / resizeWidth)
                                        / resizeSize,
                        w = resizeWidth * f,
                        h = resizeHeight * f;
                float x = corner % 2 == 1 ? resizeAnchorX : resizeAnchorX - w,
                        y = corner >= 2 ? resizeAnchorY : resizeAnchorY - h;
                config.setProperty(resizing + ".x", "" + Math.max(0, Math.round(x / hudScale)));
                config.setProperty(resizing + ".y", "" + Math.max(0, Math.round(y / hudScale)));
                return;
            }
            if (drag == null) return;
            float[] b = bounds.get(drag);
            float w = b == null ? 0 : b[2], h = b == null ? 0 : b[3];
            float[] x = snap(mx / hudScale - dx, w, hudWidth, 0),
                    y = snap(my / hudScale - dy, h, hudHeight, 1);
            guideX = x[1];
            guideY = y[1];
            config.setProperty(drag + ".x", "" + Math.max(0, Math.round(x[0])));
            config.setProperty(drag + ".y", "" + Math.max(0, Math.round(y[0])));
        }

        private float[] snap(float pos, float size, float screen, int axis) {
            List<float[]> targets = new ArrayList<float[]>();
            targets.add(new float[] {0, 0});
            targets.add(new float[] {screen - size, screen});
            targets.add(new float[] {screen / 2 - size / 2, screen / 2});
            for (Map.Entry<String, float[]> e : bounds.entrySet()) {
                if (e.getKey().equals(drag)) continue;
                float start = e.getValue()[axis], end = start + e.getValue()[axis + 2];
                targets.add(new float[] {start, start});
                targets.add(new float[] {end - size, end});
                targets.add(new float[] {end + GAP, end + GAP});
                targets.add(new float[] {start - size - GAP, start - GAP});
                targets.add(new float[] {(start + end) / 2 - size / 2, (start + end) / 2});
            }
            float[] best = null;
            for (float[] t : targets)
                if (Math.abs(pos - t[0]) < SNAP
                        && (best == null || Math.abs(pos - t[0]) < Math.abs(pos - best[0])))
                    best = t;
            return best != null ? best : new float[] {Math.round(pos / GRID) * GRID, -1};
        }

        @Override
        protected void func_146286_b(int mx, int my, int state) {
            if (volumeDrag) {
                volumeDrag = false;
                setVolume(mx / ms, true);
            }
            if (rangeDrag != null) {
                rangeDrag = null;
                save();
            }
            guideX = guideY = -1;
            if (drag != null || resizing != null) {
                drag = null;
                resizing = null;
                save();
            }
        }
    }

    private final Map<String, Integer> resourceTextures = new HashMap<String, Integer>();

    private int resourceTexture(String name) throws Exception {
        Integer id = resourceTextures.get(name);
        if (id != null) return id;
        BufferedImage img;
        try (InputStream in =
                name.startsWith("file:")
                        ? new FileInputStream(name.substring(5))
                        : getClass().getResourceAsStream("/assets/antagon/" + name)) {
            img = ImageIO.read(in);
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(img.getWidth() * img.getHeight() * 4);
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                pixels.put((byte) (p >> 16))
                        .put((byte) (p >> 8))
                        .put((byte) p)
                        .put((byte) (p >> 24));
            }
        pixels.flip();
        id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                img.getWidth(),
                img.getHeight(),
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                pixels);
        resourceTextures.put(name, id);
        return id;
    }

    private void image(
            String name, float x, float y, float w, float h, float u0, float v0, float u1, float v1)
            throws Exception {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, resourceTexture(name));
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    private static String unescapeField(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else out.append(c);
        }
        return out.toString();
    }

    private static String escapeField(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private final class RadioMenu extends GuiScreen {
        static final int W = 560, H = 340;
        final GuiScreen parent;
        final Map<String, float[]> hits = new LinkedHashMap<String, float[]>();
        String mode = "radio", genre = "Todos", selected = "";
        int listOffset, trackOffset;
        float ms = 1, x0, y0;
        List<String> genres = new ArrayList<String>();

        RadioMenu(GuiScreen parent) {
            this.parent = parent;
            NativeRadio.open();
            RadioCatalog.Collection current = NativeRadio.collection;
            if (current != null) {
                mode = current.mode;
                selected = current.id;
            }
        }

        public boolean func_73868_f() {
            return false;
        }

        boolean inside(int x, int y, float[] r) {
            return x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
        }

        void small(String s, float x, float y, float size, int color) {
            GL11.glPushMatrix();
            GL11.glTranslatef(x, y, 0);
            GL11.glScalef(size, size, 1);
            text(s, 0, 0, color);
            GL11.glPopMatrix();
        }

        void control(
                String id,
                String label,
                float x,
                float y,
                float w,
                float h,
                int mx,
                int my,
                boolean accent,
                boolean active) {
            float[] r = {x, y, w, h};
            boolean over = active && inside(mx, my, r);
            rect(x, y, w, h, over ? 0xFFB91922 : accent ? 0xFF7C1B23 : 0xFF27282E);
            outline(x, y, w, h, over ? RED : 0xFF45464E);
            float fs = Math.min(.8f, (w - 6) / Math.max(1, width(label)));
            small(
                    label,
                    x + (w - width(label) * fs) / 2,
                    y + (h - 16 * fs) / 2,
                    fs,
                    active ? WHITE : 0xFF62646E);
            if (active) hits.put(id, r);
        }

        void cover(String key, float x, float y, float size) throws Exception {
            rect(x, y, size, size, 0xFF302127);
            String art = NativeRadio.art(key);
            image(
                    art.isEmpty() ? "logo.png" : "file:" + art,
                    x + (art.isEmpty() ? size * .15f : 0),
                    y + (art.isEmpty() ? size * .15f : 0),
                    art.isEmpty() ? size * .7f : size,
                    art.isEmpty() ? size * .7f : size,
                    0,
                    0,
                    1,
                    1);
        }

        public void func_73863_a(int mx, int my, float partial) {
            try {
                ms = Math.min(1, Math.min((field_146294_l - 10f) / W, (field_146295_m - 10f) / H));
                begin(ms);
                int sw = (int) (field_146294_l / ms), sh = (int) (field_146295_m / ms);
                mx = (int) (mx / ms);
                my = (int) (my / ms);
                x0 = (sw - W) / 2f;
                y0 = (sh - H) / 2f;
                hits.clear();
                rect(0, 0, sw, sh, 0xB0000000);
                rect(x0, y0, W, H, 0xFC17181D);
                outline(x0, y0, W, H, 0xFF41424B);
                image("logo.png", x0 + 12, y0 + 9, 19, 19, 0, 0, 1, 1);
                text("RÁDIO", x0 + 39, y0 + 10, WHITE);
                control(
                        "mode:radio",
                        "AO VIVO",
                        x0 + 152,
                        y0 + 8,
                        78,
                        22,
                        mx,
                        my,
                        mode.equals("radio"),
                        true);
                control(
                        "mode:playlist",
                        "PLAYLISTS",
                        x0 + 236,
                        y0 + 8,
                        85,
                        22,
                        mx,
                        my,
                        mode.equals("playlist"),
                        true);
                control(
                        "genre",
                        "ESTILO: " + genre,
                        x0 + 332,
                        y0 + 8,
                        151,
                        22,
                        mx,
                        my,
                        false,
                        true);
                control("refresh", "R", x0 + 491, y0 + 8, 23, 22, mx, my, false, true);
                control("close", "X", x0 + 522, y0 + 8, 25, 22, mx, my, false, true);
                rect(x0 + 12, y0 + 38, W - 24, 1, 0xFF33343D);
                RadioCatalog catalog = NativeRadio.catalog;
                RadioCatalog.Collection detail = null;
                if (catalog == null)
                    small(
                            NativeRadio.catalogError.isEmpty()
                                    ? "Carregando catálogo..."
                                    : NativeRadio.catalogError,
                            x0 + 16,
                            y0 + 70,
                            1,
                            GRAY);
                else {
                    List<RadioCatalog.Collection> list = new ArrayList<RadioCatalog.Collection>();
                    TreeSet<String> styles = new TreeSet<String>();
                    styles.add("Todos");
                    for (RadioCatalog.Collection c : catalog.collections)
                        if (c.mode.equals(mode)) {
                            styles.add(c.genre);
                            if (genre.equals("Todos") || genre.equals(c.genre)) list.add(c);
                        }
                    genres = new ArrayList<String>(styles);
                    if (!genres.contains(genre)) genre = "Todos";
                    listOffset = Math.max(0, Math.min(listOffset, list.size() - 5));
                    if (!list.isEmpty()
                            && (catalog.collection(selected) == null
                                    || !list.contains(catalog.collection(selected))))
                        selected = list.get(0).id;
                    for (int i = listOffset; i < Math.min(list.size(), listOffset + 5); i++) {
                        RadioCatalog.Collection c = list.get(i);
                        float y = y0 + 49 + (i - listOffset) * 43;
                        rect(x0 + 12, y, 170, 39, c.id.equals(selected) ? 0xFF452128 : 0xFF22232A);
                        if (c.id.equals(selected)) rect(x0 + 12, y, 2, 39, RED);
                        cover(c.cover, x0 + 18, y + 4, 31);
                        small(fit(c.name, 122 / .85f), x0 + 56, y + 3, .85f, WHITE);
                        small(fit(c.genre, 120 / .7f), x0 + 56, y + 20, .7f, GRAY);
                        hits.put("collection:" + c.id, new float[] {x0 + 12, y, 170, 39});
                    }
                    detail = catalog.collection(selected);
                    if (detail != null && !list.isEmpty()) {
                        cover(detail.cover, x0 + 197, y0 + 49, 60);
                        small(fit(detail.name, 278), x0 + 268, y0 + 49, 1, WHITE);
                        small(
                                fit(
                                        detail.genre + " · " + detail.trackIds.size() + " músicas",
                                        340),
                                x0 + 268,
                                y0 + 70,
                                .8f,
                                GRAY);
                        RadioCatalog.Live live = detail.live() ? catalog.live(detail) : null;
                        boolean available =
                                detail.live() ? live != null : !detail.trackIds.isEmpty();
                        control(
                                "play:" + detail.id,
                                detail.live() ? "OUVIR AO VIVO" : "REPRODUZIR",
                                x0 + 420,
                                y0 + 91,
                                125,
                                21,
                                mx,
                                my,
                                true,
                                available);
                        small(
                                detail.live() ? "PROGRAMAÇÃO" : "MÚSICAS",
                                x0 + 197,
                                y0 + 122,
                                .75f,
                                GRAY);
                        if (!available)
                            small(
                                    "Esta seleção ainda não tem músicas.",
                                    x0 + 197,
                                    y0 + 152,
                                    .85f,
                                    GRAY);
                        trackOffset =
                                Math.max(0, Math.min(trackOffset, detail.trackIds.size() - 5));
                        for (int i = trackOffset;
                                i < Math.min(detail.trackIds.size(), trackOffset + 5);
                                i++) {
                            RadioCatalog.Track t = catalog.tracks.get(detail.trackIds.get(i));
                            if (t == null) continue;
                            float y = y0 + 142 + (i - trackOffset) * 24;
                            boolean playing =
                                    NativeRadio.track != null && t.id.equals(NativeRadio.track.id);
                            rect(x0 + 197, y, 348, 22, playing ? 0xFF422127 : 0xFF202127);
                            small(
                                    Integer.toString(i + 1),
                                    x0 + 203,
                                    y + 3,
                                    .7f,
                                    playing ? RED : GRAY);
                            small(fit(t.title, 238 / .8f), x0 + 224, y, .8f, WHITE);
                            small(fit(t.artist, 238 / .65f), x0 + 224, y + 11, .65f, GRAY);
                            small(
                                    StatusData.duration((int) (t.duration / 50)),
                                    x0 + 509,
                                    y + 3,
                                    .7f,
                                    GRAY);
                            if (!detail.live())
                                hits.put("track:" + i, new float[] {x0 + 197, y, 348, 22});
                        }
                        if (detail.trackIds.size() > 5)
                            small(
                                    (trackOffset + 1)
                                            + "-"
                                            + Math.min(trackOffset + 5, detail.trackIds.size())
                                            + " / "
                                            + detail.trackIds.size()
                                            + "   Role para ver mais",
                                    x0 + 197,
                                    y0 + 264,
                                    .65f,
                                    GRAY);
                        if (NativeRadio.track != null && NativeRadio.track.media.endsWith(".m4a"))
                            control(
                                    "launcher",
                                    "ABRIR NO LAUNCHER",
                                    x0 + 397,
                                    y0 + 260,
                                    148,
                                    18,
                                    mx,
                                    my,
                                    false,
                                    true);
                    }
                }
                rect(x0, y0 + 282, W, 58, 0xFF101116);
                RadioCatalog.Track playing = NativeRadio.track;
                RadioCatalog.Collection channel = NativeRadio.collection;
                cover(
                        playing != null && !playing.cover.isEmpty()
                                ? playing.cover
                                : channel == null ? "" : channel.cover,
                        x0 + 12,
                        y0 + 289,
                        37);
                small(
                        fit(playing == null ? "Nenhuma música tocando" : playing.title, 184 / .85f),
                        x0 + 58,
                        y0 + 287,
                        .85f,
                        WHITE);
                small(
                        fit(playing == null ? "" : playing.artist, 184 / .7f),
                        x0 + 58,
                        y0 + 301,
                        .7f,
                        GRAY);
                small(
                        fit(channel == null ? "" : channel.name, 184 / .65f),
                        x0 + 58,
                        y0 + 316,
                        .65f,
                        GRAY);
                boolean live = channel != null && channel.live();
                control(
                        "previous",
                        "<<",
                        x0 + 255,
                        y0 + 289,
                        27,
                        24,
                        mx,
                        my,
                        false,
                        playing != null && !live);
                control(
                        "toggle",
                        NativeRadio.wantPlay ? "||" : ">",
                        x0 + 287,
                        y0 + 287,
                        38,
                        28,
                        mx,
                        my,
                        true,
                        playing != null);
                control(
                        "next",
                        ">>",
                        x0 + 330,
                        y0 + 289,
                        27,
                        24,
                        mx,
                        my,
                        false,
                        playing != null && !live);
                small(
                        live ? "AO VIVO" : StatusData.duration((int) (NativeRadio.position * 20)),
                        x0 + 369,
                        y0 + 293,
                        .75f,
                        live ? RED : GRAY);
                control("less", "-", x0 + 427, y0 + 289, 20, 24, mx, my, false, true);
                small(Math.round(NativeRadio.volume * 100) + "%", x0 + 456, y0 + 294, .8f, WHITE);
                control("more", "+", x0 + 504, y0 + 289, 20, 24, mx, my, false, true);
                control("stop", "X", x0 + 529, y0 + 289, 18, 24, mx, my, false, playing != null);
                rect(x0 + 255, y0 + 322, 290, 2, 0xFF35363D);
                if (playing != null) {
                    rect(
                            x0 + 255,
                            y0 + 322,
                            (float) Math.min(1, NativeRadio.position * 1000 / playing.duration)
                                    * 290,
                            2,
                            RED);
                    if (!live) hits.put("seek", new float[] {x0 + 255, y0 + 317, 290, 12});
                }
                String notice =
                        !NativeRadio.error.isEmpty()
                                ? NativeRadio.error
                                : NativeRadio.state.equals("buffering")
                                        ? "Carregando música..."
                                        : NativeRadio.catalogError;
                if (!notice.isEmpty())
                    small(fit(notice, (W - 24) / .7f), x0 + 12, y0 + H - 13, .7f, GRAY);
            } catch (Throwable e) {
                report(e);
            } finally {
                end();
            }
        }

        protected void func_73864_a(int mx, int my, int button) throws IOException {
            if (button != 0) return;
            mx = (int) (mx / ms);
            my = (int) (my / ms);
            for (Map.Entry<String, float[]> entry : hits.entrySet())
                if (inside(mx, my, entry.getValue())) {
                    String action = entry.getKey();
                    try {
                        if (action.equals("close"))
                            call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, parent);
                        else if (action.equals("refresh")) NativeRadio.refreshAsync();
                        else if (action.equals("launcher")) {
                            NativeRadio.stop();
                            requestLauncherView("radio");
                        } else if (action.startsWith("mode:")) {
                            mode = action.substring(5);
                            selected = "";
                            listOffset = trackOffset = 0;
                            genre = "Todos";
                        } else if (action.equals("genre") && !genres.isEmpty()) {
                            genre = genres.get((genres.indexOf(genre) + 1) % genres.size());
                            selected = "";
                            listOffset = trackOffset = 0;
                        } else if (action.startsWith("collection:")) {
                            selected = action.substring(11);
                            trackOffset = 0;
                        } else if (action.startsWith("play:") || action.startsWith("track:")) {
                            NativeRadio.select(
                                    action.startsWith("play:") ? action.substring(5) : selected,
                                    action.startsWith("track:")
                                            ? Integer.parseInt(action.substring(6))
                                            : 0);
                            config.setProperty("radio", "true");
                            save();
                        } else if (action.equals("toggle")) NativeRadio.toggle();
                        else if (action.equals("previous")) NativeRadio.next(-1);
                        else if (action.equals("next")) NativeRadio.next(1);
                        else if (action.equals("stop")) NativeRadio.stop();
                        else if (action.equals("less"))
                            NativeRadio.volume(Math.round(NativeRadio.volume * 100) - 5);
                        else if (action.equals("more"))
                            NativeRadio.volume(Math.round(NativeRadio.volume * 100) + 5);
                        else if (action.equals("seek") && NativeRadio.track != null)
                            NativeRadio.seek(
                                    (mx - (x0 + 255)) / 290 * NativeRadio.track.duration / 1000);
                    } catch (Exception e) {
                        report(e);
                    }
                    return;
                }
        }

        public void func_146274_d() throws IOException {
            super.func_146274_d();
            int delta = Mouse.getEventDWheel();
            if (delta == 0) return;
            float x =
                    Mouse.getEventX()
                            * (float) field_146294_l
                            / ((Number) getDisplayWidth()).floatValue()
                            / ms;
            if (x < x0 + 190) listOffset = Math.max(0, listOffset - (delta > 0 ? 1 : -1));
            else trackOffset = Math.max(0, trackOffset - (delta > 0 ? 1 : -1));
        }

        private Number getDisplayWidth() {
            try {
                return (Number) field(mc, "field_71443_c", "displayWidth");
            } catch (Exception e) {
                return field_146294_l;
            }
        }

        protected void func_73869_a(char c, int key) throws IOException {
            if (key == Keyboard.KEY_ESCAPE)
                try {
                    call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, parent);
                } catch (Exception e) {
                    report(e);
                }
            else if (key == Keyboard.KEY_SPACE) NativeRadio.toggle();
        }
    }

    /**
     * In-game friends, chat and store. The launcher owns the session; this screen only reads its
     * snapshot (antagon-ui-state.txt) and leaves commands for it (antagon-ui-cmd-*.txt).
     */
    private Hub smokeHub;

    private final class Hub extends GuiScreen {
        static final int W = 460, H = 280;
        final GuiScreen parent;
        String tab;
        float ms = 1, x0, y0;
        long lastRead = 0, updated = 0;
        boolean online = false;
        String me = "", notice = "", chat = null, typing = null, addText = "", chatText = "";
        int balance = -1, listScroll = 0, page = 0;
        final List<String[]> friends = new ArrayList<String[]>(),
                incoming = new ArrayList<String[]>(),
                outgoing = new ArrayList<String[]>(),
                messages = new ArrayList<String[]>(),
                items = new ArrayList<String[]>();
        final Map<String, float[]> hits = new LinkedHashMap<String, float[]>();

        Hub(GuiScreen parent, String tab) {
            this.parent = parent;
            this.tab = tab;
            command("store");
        }

        void command(String... parts) {
            try {
                StringBuilder line = new StringBuilder();
                for (String part : parts)
                    line.append(line.length() == 0 ? "" : "\t").append(escapeField(part));
                File temporary = new File("antagon-ui-cmd.tmp");
                Files.write(temporary.toPath(), line.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(
                        temporary.toPath(),
                        new File("antagon-ui-cmd-" + System.nanoTime() + ".txt").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                report(e);
            }
        }

        void read() {
            if (System.currentTimeMillis() - lastRead < 400) return;
            lastRead = System.currentTimeMillis();
            File file = new File("antagon-ui-state.txt");
            if (!file.isFile()) return;
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                friends.clear();
                incoming.clear();
                outgoing.clear();
                messages.clear();
                items.clear();
                String stateChat = null;
                for (String line : lines) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq);
                    String[] v = line.substring(eq + 1).split("\t", -1);
                    for (int i = 0; i < v.length; i++) v[i] = unescapeField(v[i]);
                    if (key.equals("updated")) updated = Long.parseLong(v[0]);
                    else if (key.equals("online")) online = v[0].equals("1");
                    else if (key.equals("me")) me = v[0];
                    else if (key.equals("notice")) notice = v[0];
                    else if (key.equals("friend") && v.length >= 6) friends.add(v);
                    else if (key.equals("incoming") && v.length >= 2) incoming.add(v);
                    else if (key.equals("outgoing") && v.length >= 2) outgoing.add(v);
                    else if (key.equals("chat")) stateChat = v[0];
                    else if (key.equals("msg") && v.length >= 3) messages.add(v);
                    else if (key.equals("balance")) balance = Integer.parseInt(v[0]);
                    else if (key.equals("item") && v.length >= 7) items.add(v);
                }
                if (chat != null && !chat.equals(stateChat)) messages.clear();
            } catch (Exception e) {
                report(e);
            }
        }

        boolean in(int mx, int my, float x, float y, float w, float h) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }

        void button(
                String id,
                String label,
                float x,
                float y,
                float w,
                float h,
                int mx,
                int my,
                boolean primary) {
            boolean hover = in(mx, my, x, y, w, h);
            rect(
                    x,
                    y,
                    w,
                    h,
                    primary ? (hover ? 0xFFFF3A3A : RED) : (hover ? 0xFF3C3C40 : 0xFF2C2C30));
            text(label, x + (w - width(label)) / 2, y + h / 2 - 7.5f, WHITE);
            hits.put(id, new float[] {x, y, w, h});
        }

        void field(String id, String value, String placeholder, float x, float y, float w) {
            boolean active = id.equals(typing);
            rect(x, y, w, 18, active ? 0xFF2A1416 : 0xFF121214);
            outline(x, y, w, 18, active ? RED : 0xFF353539);
            String shown = value.isEmpty() && !active ? placeholder : value;
            if (active && System.currentTimeMillis() / 500 % 2 == 0) shown += "_";
            String visible = shown;
            while (visible.length() > 1 && width(visible) > w - 10) visible = visible.substring(1);
            text(visible, x + 5, y + 2, value.isEmpty() && !active ? GRAY : WHITE);
            hits.put("field:" + id, new float[] {x, y, w, 18});
        }

        String[] friend(String id) {
            for (String[] f : friends) if (f[0].equals(id)) return f;
            return null;
        }

        @Override
        public boolean func_73868_f() {
            return false;
        }

        @Override
        public void func_73863_a(int mx, int my, float partial) {
            try {
                read();
                hits.clear();
                ms = Math.min(1, Math.min((field_146295_m - 8f) / H, (field_146294_l - 8f) / W));
                begin(ms);
                int w = (int) (field_146294_l / ms), h = (int) (field_146295_m / ms);
                mx = (int) (mx / ms);
                my = (int) (my / ms);
                rect(0, 0, w, h, 0x99000000);
                x0 = (w - W) / 2;
                y0 = (h - H) / 2;
                rect(x0 + 4, y0 + 5, W, H, 0x60000000);
                rect(x0, y0, W, H, 0xFA19191C);
                outline(x0, y0, W, H, 0xFF353539);
                image("logo.png", x0 + 12, y0 + 8, 16, 16, 0, 0, 1, 1);
                text("ANTAGON", x0 + 34, y0 + 8, WHITE);
                String[] tabs = {"friends", "store", "inventory"},
                        labels = {"AMIGOS", "LOJA", "INVENTÁRIO"};
                for (int i = 0; i < tabs.length; i++) {
                    float tx = x0 + 116 + i * 100, tw = 94;
                    boolean active = tabs[i].equals(tab);
                    rect(tx, y0 + 6, tw, 20, active ? 0xFF3A1A1C : 0xFF2C2C30);
                    if (active) rect(tx, y0 + 24, tw, 2, RED);
                    text(
                            labels[i],
                            tx + (tw - width(labels[i])) / 2,
                            y0 + 8,
                            active ? WHITE : GRAY);
                    hits.put("tab:" + tabs[i], new float[] {tx, y0 + 6, tw, 20});
                }
                button("close", "X", x0 + W - 28, y0 + 6, 18, 20, mx, my, false);
                rect(x0 + 10, y0 + 32, W - 20, 1, 0xFF303035);
                boolean stale = updated == 0 || System.currentTimeMillis() - updated > 6000;
                if (stale || !online) {
                    String line =
                            stale
                                    ? "ABRA O LAUNCHER DO ANTAGON CLIENT PARA USAR AMIGOS E LOJA."
                                    : "ENTRE COM SUA CONTA MICROSOFT NO LAUNCHER.";
                    text(line, x0 + (W - width(line)) / 2, y0 + H / 2 - 8, GRAY);
                } else if (tab.equals("friends")) drawFriends(mx, my);
                else drawStore(mx, my, tab.equals("inventory"));
                if (!notice.isEmpty()) text(fit(notice, W - 24), x0 + 12, y0 + H - 16, GRAY);
            } catch (Throwable e) {
                report(e);
            } finally {
                end();
            }
        }

        void drawFriends(int mx, int my) throws Exception {
            float lx = x0 + 10, lw = 170, top = y0 + 40;
            field("add", addText, "NICK DO AMIGO", lx, top, lw - 26);
            button("add", "+", lx + lw - 22, top, 22, 18, mx, my, true);
            List<String[]> rows = new ArrayList<String[]>();
            if (!incoming.isEmpty()) rows.add(new String[] {"#", "PEDIDOS"});
            for (String[] p : incoming) rows.add(new String[] {"in", p[0], p[1]});
            int onlineCount = 0;
            for (String[] f : friends) if (f[2].equals("1")) onlineCount++;
            rows.add(new String[] {"#", "AMIGOS - " + onlineCount + " ONLINE"});
            List<String[]> sorted = new ArrayList<String[]>(friends);
            Collections.sort(
                    sorted,
                    (a, b) ->
                            a[2].equals(b[2])
                                    ? a[1].compareToIgnoreCase(b[1])
                                    : b[2].compareTo(a[2]));
            for (String[] f : sorted) rows.add(new String[] {"f", f[0]});
            for (String[] p : outgoing) rows.add(new String[] {"out", p[0], p[1]});
            float y = top + 26, bottom = y0 + H - 24;
            float listEnd = bottom;
            int max = Math.max(0, rows.size() - 8);
            listScroll = Math.max(0, Math.min(listScroll, max));
            for (int i = listScroll; i < rows.size() && y < listEnd - 18; i++) {
                String[] row = rows.get(i);
                if (row[0].equals("#")) {
                    text(row[1], lx, y + 2, GRAY);
                    y += 18;
                } else if (row[0].equals("in") || row[0].equals("out")) {
                    rect(lx, y, lw, 20, 0xFF232327);
                    text(fit(row[2], lw - 60), lx + 6, y + 2, WHITE);
                    if (row[0].equals("in")) {
                        button("accept:" + row[1], "+", lx + lw - 44, y + 2, 20, 16, mx, my, true);
                        button("remove:" + row[1], "X", lx + lw - 22, y + 2, 20, 16, mx, my, false);
                    } else
                        button("remove:" + row[1], "X", lx + lw - 22, y + 2, 20, 16, mx, my, false);
                    y += 22;
                } else {
                    String[] f = friend(row[1]);
                    boolean on = f[2].equals("1"), selected = f[0].equals(chat);
                    rect(
                            lx,
                            y,
                            lw,
                            28,
                            selected
                                    ? 0xFF3A1A1C
                                    : in(mx, my, lx, y, lw, 28) ? 0xFF2C2C30 : 0xFF232327);
                    rect(lx + 6, y + 6, 4, 4, on ? 0xFF7BD66A : 0xFF55555A);
                    text(fit(f[1], lw - 20), lx + 14, y + 1, on ? WHITE : GRAY);
                    text(fit(activity(f), lw - 20), lx + 14, y + 13, GRAY);
                    hits.put("friend:" + f[0], new float[] {lx, y, lw, 28});
                    y += 30;
                }
            }
            float cx = x0 + 192, cw = W - 202;
            rect(cx - 6, top, 1, bottom - top, 0xFF303035);
            String[] f = chat == null ? null : friend(chat);
            if (f == null) {
                String line = "ESCOLHA UM AMIGO PARA CONVERSAR.";
                text(line, cx + (cw - width(line)) / 2, y0 + H / 2 - 8, GRAY);
                return;
            }
            text(f[1], cx, top + 1, WHITE);
            text(fit(activity(f), cw - 70), cx, top + 13, GRAY);
            if (f[3].equals("server") && !f[4].isEmpty())
                button("join:" + f[4], "ENTRAR", cx + cw - 60, top, 60, 20, mx, my, true);
            rect(cx, top + 28, cw, 1, 0xFF303035);
            List<String[]> lines = new ArrayList<String[]>();
            for (String[] m : messages)
                for (String part : wrap(m[2], cw - 24)) lines.add(new String[] {m[0], part});
            float my2 = bottom - 38;
            for (int i = lines.size() - 1; i >= 0 && my2 > top + 32; i--) {
                String[] l = lines.get(i);
                float tw = width(l[1]) + 10, bx = l[0].equals("1") ? cx + cw - tw : cx;
                rect(bx, my2, tw, 14, l[0].equals("1") ? 0xFFB01212 : 0xFF2C2C30);
                text(l[1], bx + 5, my2, WHITE);
                my2 -= 16;
            }
            field("chat", chatText, "MENSAGEM", cx, bottom - 18, cw - 64);
            button("send", "ENVIAR", cx + cw - 60, bottom - 18, 60, 18, mx, my, true);
        }

        String activity(String[] f) {
            if (!f[2].equals("1")) return "OFFLINE";
            if (f[3].equals("server")) return "JOGANDO EM " + f[4].toUpperCase();
            if (f[3].equals("singleplayer")) return "SINGLEPLAYER";
            if (f[3].equals("menu")) return "NO MENU";
            if (f[3].equals("launcher")) return "NO LAUNCHER";
            return "JOGANDO";
        }

        List<String> wrap(String text, float max) {
            List<String> out = new ArrayList<String>();
            for (String paragraph : text.split("\n")) {
                String line = "";
                for (String word : paragraph.split(" ")) {
                    String next = line.isEmpty() ? word : line + " " + word;
                    if (width(next) <= max || line.isEmpty()) line = next;
                    else {
                        out.add(line);
                        line = word;
                    }
                    while (width(line) > max && line.length() > 1) {
                        int cut = line.length() - 1;
                        while (cut > 1 && width(line.substring(0, cut)) > max) cut--;
                        out.add(line.substring(0, cut));
                        line = line.substring(cut);
                    }
                }
                out.add(line);
            }
            return out;
        }

        /**
         * Cape previews show the outer face; custom capes are read from the launcher's download.
         */
        void preview(String[] item, float x, float y) throws Exception {
            if (item[6].equals("hat")) {
                image("crown-preview.png", x - 16, y + 18, 72, 32, 0, 0, 1, 1);
                return;
            }
            String texture = item[0].equals("antagon_logo_cape") ? "logo-cape.png" : "cape.png";
            if (item[0].startsWith("custom_")) {
                texture = "file:antagon-capes/" + item[0] + ".png";
                if (!new File(texture.substring(5)).isFile()) {
                    rect(x, y, 40, 64, 0xFF2C2C30);
                    return;
                }
            }
            image(texture, x, y, 40, 64, 1 / 64f, 1 / 32f, 11 / 64f, 17 / 32f);
        }

        void drawStore(int mx, int my, boolean inventory) throws Exception {
            float top = y0 + 40;
            image("store.png", x0 + 12, top, 16, 16, 0, 0, 1, 1);
            text(
                    (balance < 0 ? "--" : String.valueOf(balance)) + " ANTAGOIN$",
                    x0 + 32,
                    top + 1,
                    WHITE);
            List<String[]> shown = new ArrayList<String[]>();
            for (String[] item : items)
                if ((inventory ? item[3] : item[5]).equals("1")) shown.add(item);
            int per = 4, pages = Math.max(1, (shown.size() + per - 1) / per);
            page = Math.max(0, Math.min(page, pages - 1));
            if (pages > 1) {
                String label = (page + 1) + "/" + pages;
                text(label, x0 + W - 58 - width(label), top + 1, GRAY);
                button("page:-1", "<", x0 + W - 52, top - 2, 18, 18, mx, my, false);
                button("page:1", ">", x0 + W - 30, top - 2, 18, 18, mx, my, false);
            }
            float cardW = 100, cardH = inventory ? 176 : 150, gap = 8, cy = top + 24;
            if (shown.isEmpty()) {
                String line =
                        inventory
                                ? "VOCÊ AINDA NÃO TEM COSMÉTICOS."
                                : "NENHUM ITEM À VENDA NO MOMENTO.";
                text(line, x0 + (W - width(line)) / 2, cy + 50, GRAY);
            }
            float cx = x0 + (W - (per * cardW + (per - 1) * gap)) / 2;
            for (int i = page * per; i < Math.min(shown.size(), page * per + per); i++) {
                String[] item = shown.get(i);
                boolean owned = item[3].equals("1"), equipped = item[4].equals("1");
                rect(cx, cy, cardW, cardH, 0xFF232327);
                outline(cx, cy, cardW, cardH, equipped ? RED : 0xFF303035);
                preview(item, cx + (cardW - 40) / 2, cy + 8);
                List<String> name = wrap(item[1].toUpperCase(), cardW - 10);
                for (int l = 0; l < Math.min(2, name.size()); l++)
                    text(name.get(l), cx + 6, cy + 76 + l * 13, WHITE);
                String state =
                        equipped ? "EQUIPADO" : owned ? "NO INVENTÁRIO" : item[2] + " ANTAGOIN$";
                text(fit(state, cardW - 10), cx + 6, cy + 104, equipped ? RED : GRAY);
                String label = equipped ? "TIRAR" : owned ? "EQUIPAR" : "COMPRAR";
                String action =
                        equipped
                                ? "unequip:" + item[6]
                                : owned ? "equip:" + item[0] : "buy:" + item[0];
                button(action, label, cx + 6, cy + cardH - 26, cardW - 12, 20, mx, my, !equipped);
                cx += cardW + gap;
            }
            if (inventory) return;
            String[][] packs = {
                {"small", "100", "R$ 4,90"},
                {"medium", "550", "R$ 19,90"},
                {"large", "1.200", "R$ 39,90"}
            };
            float py = cy + cardH + 12;
            text("COMPRAR ANTAGOIN$ (ABRE NO NAVEGADOR)", x0 + 12, py, GRAY);
            for (int i = 0; i < packs.length; i++)
                button(
                        "coins:" + packs[i][0],
                        packs[i][1] + " ANTAGOIN$  " + packs[i][2],
                        x0 + 12 + i * 146,
                        py + 14,
                        140,
                        20,
                        mx,
                        my,
                        false);
        }

        void submit() {
            if ("add".equals(typing) && !addText.trim().isEmpty()) {
                command("add", addText.trim());
                addText = "";
            } else if ("chat".equals(typing) && chat != null && !chatText.trim().isEmpty()) {
                command("send", chat, chatText.trim());
                chatText = "";
            }
        }

        @Override
        protected void func_73869_a(char c, int key) {
            if (typing != null) {
                boolean paste =
                        key == Keyboard.KEY_V
                                && (Keyboard.isKeyDown(Keyboard.KEY_LMETA)
                                        || Keyboard.isKeyDown(Keyboard.KEY_RMETA)
                                        || Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                                        || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
                String value = typing.equals("add") ? addText : chatText;
                int limit = typing.equals("add") ? 16 : 500;
                if (key == Keyboard.KEY_ESCAPE) typing = null;
                else if (key == Keyboard.KEY_RETURN) submit();
                else if (key == Keyboard.KEY_BACK) {
                    if (!value.isEmpty()) value = value.substring(0, value.length() - 1);
                } else if (paste) value += Platform.clipboard().replaceAll("[\\p{Cntrl}]", " ");
                else if (c >= 32 && c != 127 && c != 167) value += c;
                if (value.length() > limit) value = value.substring(0, limit);
                if (typing != null && typing.equals("add"))
                    addText = value.replaceAll("[^A-Za-z0-9_]", "");
                else if (typing != null) chatText = value;
                return;
            }
            if (key == Keyboard.KEY_ESCAPE) close();
        }

        @Override
        public void func_146274_d() throws IOException {
            super.func_146274_d();
            int wheel = Mouse.getEventDWheel();
            if (wheel != 0) listScroll += wheel > 0 ? -1 : 1;
        }

        void close() {
            try {
                call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, parent);
            } catch (Exception e) {
                report(e);
            }
        }

        @Override
        protected void func_73864_a(int mx, int my, int mouseButton) {
            if (mouseButton != 0) return;
            mx = (int) (mx / ms);
            my = (int) (my / ms);
            String hit = null;
            for (Map.Entry<String, float[]> e : hits.entrySet()) {
                float[] b = e.getValue();
                if (in(mx, my, b[0], b[1], b[2], b[3])) hit = e.getKey();
            }
            typing = null;
            if (hit == null) return;
            if (hit.equals("close")) close();
            else if (hit.startsWith("tab:")) {
                tab = hit.substring(4);
                page = 0;
                if (!tab.equals("friends")) command("store");
            } else if (hit.startsWith("field:")) typing = hit.substring(6);
            else if (hit.equals("add")) {
                typing = "add";
                submit();
                typing = null;
            } else if (hit.equals("send")) {
                typing = "chat";
                submit();
            } else if (hit.startsWith("friend:")) {
                chat = hit.substring(7);
                messages.clear();
                command("chat", chat);
                typing = "chat";
            } else if (hit.startsWith("accept:")) command("accept", hit.substring(7));
            else if (hit.startsWith("remove:")) command("remove", hit.substring(7));
            else if (hit.startsWith("buy:")) command("buy", hit.substring(4));
            else if (hit.startsWith("equip:")) command("equip", hit.substring(6));
            else if (hit.startsWith("unequip:")) command("unequip", hit.substring(8));
            else if (hit.startsWith("page:")) page += Integer.parseInt(hit.substring(5));
            else if (hit.startsWith("coins:")) command("coins", hit.substring(6));
            else if (hit.startsWith("join:")) joinServer(hit.substring(5));
        }
    }

    @SubscribeEvent
    public void nametag(RenderPlayerEvent.Post event) {
        try {
            Object player = field(event, "entityPlayer");
            boolean self = player == field(mc, "field_71439_g", "thePlayer");
            int view =
                    ((Number)
                                    field(
                                            field(mc, "field_71474_y", "gameSettings"),
                                            "field_74320_O",
                                            "thirdPersonView"))
                            .intValue();
            if (self && (view == 0 || field(mc, "field_71462_r", "currentScreen") != null)) return;
            if ((Boolean) call(player, new String[] {"func_82150_aj", "isInvisible"})) return;
            String tag = Cosmetics.tagFor(player);
            boolean own = self && enabled("ownnametag");
            if (!own && tag == null) return;
            double x = event.x, y = event.y, z = event.z, distance = x * x + y * y + z * z;
            if (distance > 64 * 64) return;
            double scale = 0.02666667, line = 10 * scale;
            double base =
                    y + ((Number) field(player, "field_70131_O", "height")).floatValue() + 0.5;
            Object board =
                    call(
                            field(mc, "field_71441_e", "theWorld"),
                            new String[] {"func_96441_U", "getScoreboard"});
            if (distance < 100
                    && call(board, new String[] {"func_96539_a", "getObjectiveInDisplaySlot"}, 2)
                            != null) base += 9 * 1.15 * scale;
            if (own) {
                Object name = call(player, new String[] {"func_145748_c_", "getDisplayName"});
                billboard(
                        x,
                        base,
                        z,
                        (String) call(name, new String[] {"func_150254_d", "getFormattedText"}),
                        view);
            }
            if (tag != null) billboard(x, base + line, z, tag, view);
        } catch (Exception e) {
            report(e);
        }
    }

    /**
     * Vanilla's nametag drawing, done only through GlStateManager so its state cache stays true.
     */
    private void billboard(double x, double y, double z, String text, int view) throws Exception {
        Object rm = call(mc, new String[] {"func_175598_ae", "getRenderManager"});
        Object font = field(mc, "field_71466_p", "fontRendererObj");
        Class<?> gl = Class.forName("net.minecraft.client.renderer.GlStateManager");
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glNormal3f(0, 1, 0);
        GL11.glRotatef(-getF(rm, "field_78735_i"), 0, 1, 0);
        GL11.glRotatef((view == 2 ? -1 : 1) * getF(rm, "field_78732_j"), 1, 0, 0);
        GL11.glScalef(-0.02666667f, -0.02666667f, 0.02666667f);
        invoke(gl, null, new String[] {"func_179140_f", "disableLighting"});
        invoke(gl, null, new String[] {"func_179132_a", "depthMask"}, false);
        invoke(gl, null, new String[] {"func_179097_i", "disableDepth"});
        invoke(gl, null, new String[] {"func_179147_l", "enableBlend"});
        invoke(gl, null, new String[] {"func_179120_a", "tryBlendFuncSeparate"}, 770, 771, 1, 0);
        int half = stringWidth(font, text) / 2;
        invoke(gl, null, new String[] {"func_179090_x", "disableTexture2D"});
        invoke(gl, null, new String[] {"func_179131_c", "color"}, 0f, 0f, 0f, 0.25f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-half - 1, -1, 0);
        GL11.glVertex3f(-half - 1, 8, 0);
        GL11.glVertex3f(half + 1, 8, 0);
        GL11.glVertex3f(half + 1, -1, 0);
        GL11.glEnd();
        invoke(gl, null, new String[] {"func_179098_w", "enableTexture2D"});
        drawString(font, text, -half, 0, 0x20FFFFFF);
        invoke(gl, null, new String[] {"func_179126_j", "enableDepth"});
        invoke(gl, null, new String[] {"func_179132_a", "depthMask"}, true);
        drawString(font, text, -half, 0, -1);
        invoke(gl, null, new String[] {"func_179145_e", "enableLighting"});
        invoke(gl, null, new String[] {"func_179084_k", "disableBlend"});
        invoke(gl, null, new String[] {"func_179131_c", "color"}, 1f, 1f, 1f, 1f);
        GL11.glPopMatrix();
    }

    private void joinServer(String server) {
        if (!server.matches("[A-Za-z0-9.-]{1,253}(:\\d{1,5})?")) return;
        try {
            Object data =
                    Class.forName("net.minecraft.client.multiplayer.ServerData")
                            .getConstructor(String.class, String.class, boolean.class)
                            .newInstance("Antagon", server, false);
            Object world = field(mc, "field_71441_e", "theWorld");
            if (world != null) {
                call(world, new String[] {"func_72882_A", "sendQuittingDisconnectingPacket"});
                call(mc, new String[] {"func_71403_a", "loadWorld"}, (Object) null);
            }
            Object fml =
                    invoke(
                            Class.forName("net.minecraftforge.fml.client.FMLClientHandler"),
                            null,
                            new String[] {"instance"});
            Object title = Class.forName("net.minecraft.client.gui.GuiMainMenu").newInstance();
            call(fml, new String[] {"connectToServer"}, title, data);
        } catch (Exception e) {
            report(e);
        }
    }
}
