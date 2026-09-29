package studio.antagon;

import static studio.antagon.Reflect.*;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.ByteBuffer;
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
    private static final String[] MODS = {
        "fps",
        "cps",
        "keys",
        "coords",
        "ping",
        "clock",
        "nohurtcam",
        "togglesprint",
        "perspective",
        "crosshair",
        "chat",
        "notitles",
        "radio",
        "reach",
        "combo",
        "hitbox",
        "hitcolor"
    };
    private static final String[] NAMES = {
        "FPS",
        "CPS",
        "KEYSTROKES",
        "COORDENADAS",
        "PING",
        "RELÓGIO",
        "NO HURT CAM",
        "TOGGLE SPRINT",
        "PERSPECTIVE",
        "CROSSHAIR",
        "CHAT",
        "NO TITLES",
        "RÁDIO ANTAGON",
        "REACH DISPLAY",
        "COMBO COUNTER",
        "HITBOX",
        "HIT COLOR"
    };
    private static final boolean[] DEFAULT_ON = {
        true, true, true, false, true, false, false, false, false, false, false, false, false,
        false, false, false, false
    };
    private static final String COLOR_VALUES = "branco,vermelho,amarelo,verde,ciano";
    private static final String[] SIZE = {"size", "TAMANHO", "50%,75%,100%,125%,150%,200%", "100%"};
    private static final String[] COLOR_NAMES = {"branco", "vermelho", "amarelo", "verde", "ciano"};
    private static final int[] COLORS = {
        0xFFF0EEE8, 0xFFEE1515, 0xFFFFD23F, 0xFF7BD66A, 0xFF5FD4E8
    };
    private static final int RED = 0xFFEE1515, WHITE = 0xFFF0EEE8, GRAY = 0xFF8A8883;
    private Object mc;
    private Class<?> minecraft;
    private final LinkedList<Long> left = new LinkedList<Long>(), right = new LinkedList<Long>();
    private final Properties config = new Properties();
    private final File configFile = new File("config/antagon-hud.properties");
    private long lastLoad = 0, modified = -1;
    private boolean hidden = false, f8 = false, reported = false;
    private int texture = 0;
    private int smokeTicks = 0, worldTicks = 0;
    private boolean smokeStarted = false;
    private final int[] widths = new int[256];
    private static final int CELL = 32, ATLAS = 512;
    private final Map<String, float[]> bounds = new HashMap<String, float[]>();
    private float hudScale = 1;
    private Menu menu;
    private boolean sprintToggled = false, sprintWas = false;
    private boolean smokeConfig = false;
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
                        + " / Right Shift menu");
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
        return on(mod, DEFAULT_ON[Arrays.asList(MODS).indexOf(mod)]);
    }

    private float number(String key, float fallback) {
        try {
            return Float.parseFloat(config.getProperty(key, "" + fallback));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String[][] options(String mod) {
        if (mod.equals("nohurtcam")) return new String[0][];
        if (mod.equals("perspective"))
            return new String[][] {
                {"key", "TECLA", "key", "F"}, {"mode", "MODO", "segurar,alternar"}
            };
        if (mod.equals("crosshair"))
            return new String[][] {
                {"style", "ESTILO", "cruz,ponto,cruz + ponto"},
                {"color", "COR", COLOR_VALUES},
                {"size", "TAMANHO", "4,6,8,10,12,16", "8"},
                {"thick", "ESPESSURA", "1,2,3,4", "2"},
                {"gap", "ESPAÇO", "0,2,4,6", "2"},
                {"outline", "CONTORNO", "on,off"}
            };
        if (mod.equals("chat"))
            return new String[][] {
                {"time", "HORÁRIO", "off,on"},
                {"stack", "EMPILHAR REPETIDAS", "on,off"},
                {"scale", "TAMANHO DO TEXTO", "100%,75%,50%"},
                {"width", "LARGURA", "100%,75%,50%"},
                {"lines", "LINHAS VISÍVEIS", "10,5,15,20"}
            };
        if (mod.equals("radio")) {
            List<String[]> radio =
                    new ArrayList<String[]>(
                            Arrays.asList(
                                    new String[][] {
                                        {"playlist", "PLAYLIST", "action"},
                                        {"controls", "CONTROLES", "action"},
                                        {"volume", "VOLUME", "slider"},
                                        SIZE,
                                        {"art", "CAPA DO ÁLBUM", "on,off"},
                                        {"bg", "FUNDO", "on,off"},
                                        {"bar", "BARRA VERMELHA", "on,off"},
                                        {"prev", "TECLA: VOLTAR", "key", "NENHUMA"},
                                        {"pause", "TECLA: PAUSAR", "key", "NENHUMA"},
                                        {"next", "TECLA: PASSAR", "key", "NENHUMA"}
                                    }));
            if (!Spotify.HAS_VOLUME) radio.remove(2);
            return radio.toArray(new String[0][]);
        }
        if (mod.equals("hitbox"))
            return new String[][] {
                {"color", "COR", COLOR_VALUES},
                {"thick", "ESPESSURA", "1,2,3,4", "2"},
                {"players", "SÓ JOGADORES", "off,on"},
                {"margin", "HITBOX REAL (+0.1)", "on,off"}
            };
        if (mod.equals("hitcolor"))
            return new String[][] {
                {"color", "COR", COLOR_VALUES, "vermelho"},
                {"alpha", "INTENSIDADE", "15%,30%,45%,60%", "30%"}
            };
        if (mod.equals("notitles"))
            return new String[][] {{"actionbar", "BARRA DE AÇÃO", "off,on"}};
        if (mod.equals("keys"))
            return new String[][] {
                SIZE,
                {"bg", "FUNDO", "on,off"},
                {"mouse", "BOTÕES DO MOUSE", "on,off"},
                {"space", "BARRA DE ESPAÇO", "on,off"},
                {"active", "COR AO APERTAR", "vermelho,branco,amarelo,verde,ciano"}
            };
        List<String[]> list =
                new ArrayList<String[]>(
                        Arrays.asList(
                                new String[][] {
                                    SIZE,
                                    {"bg", "FUNDO", "on,off"},
                                    {"bar", "BARRA VERMELHA", "on,off"},
                                    {"color", "COR DO TEXTO", COLOR_VALUES}
                                }));
        if (mod.equals("cps")) list.add(new String[] {"right", "CLIQUE DIREITO", "on,off"});
        if (mod.equals("clock")) list.add(new String[] {"format", "FORMATO", "24h,12h"});
        if (mod.equals("togglesprint"))
            list.add(0, new String[] {"hud", "MOSTRAR NO HUD", "on,off"});
        return list.toArray(new String[0][]);
    }

    private String opt(String mod, String key) {
        for (String[] o : options(mod))
            if (o[0].equals(key))
                return config.getProperty(
                        mod + "." + key, o.length > 3 ? o[3] : o[2].split(",")[0]);
        return "";
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
        String v = opt(mod, key);
        if (v.equals("ALT")) return Keyboard.KEY_LMENU;
        int code = Keyboard.getKeyIndex(v);
        return code == Keyboard.KEY_NONE ? -1 : code;
    }

    private static int colorOf(String name) {
        return COLORS[Math.max(0, Arrays.asList(COLOR_NAMES).indexOf(name))];
    }

    private void prune(LinkedList<Long> clicks) {
        long cutoff = System.currentTimeMillis() - 1000;
        while (!clicks.isEmpty() && clicks.getFirst() < cutoff) clicks.removeFirst();
    }

    @SubscribeEvent
    public void mouse(MouseEvent event) {
        if (!event.buttonstate) return;
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
        if (!enabled("radio")) return;
        if (k == keyOf("radio", "prev")) Spotify.previous();
        else if (k == keyOf("radio", "pause")) radioToggle();
        else if (k == keyOf("radio", "next")) Spotify.next();
    }

    private void openMenu() {
        try {
            call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, new Menu());
        } catch (Exception e) {
            System.err.println("[ANTAGON] Menu error: " + e);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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

    private void radioPlay() {
        Spotify.play(config.getProperty("radio.uri"));
    }

    private void radioToggle() {
        if (radioState.equals("off") || radioState.equals("stopped")) radioPlay();
        else Spotify.playPause();
    }

    private void radioLoop() {
        int off = 0;
        while (true) {
            try {
                if (smokeConfig) {
                    Thread.sleep(1000);
                    continue;
                }
                if (enabled("radio")) {
                    off = 0;
                    String[] p = Spotify.status();
                    if (p.length >= 7 && System.currentTimeMillis() - volumeSentAt > 2500)
                        radioVolume = (int) Spotify.number(p[6]);
                    if (p.length >= 6) {
                        radioTitle = p[1];
                        radioArtist = p[2];
                        radioPos = Spotify.number(p[4]);
                        radioDur = Spotify.number(p[5]) / 1000f;
                        radioStamp = System.currentTimeMillis();
                        radioState = p[0];
                        if (!p[3].equals(radioArt)) {
                            radioArt = p[3];
                            loadArt(p[3]);
                        }
                    } else {
                        radioState = p[0].isEmpty() ? "off" : p[0];
                        radioTitle = "";
                    }
                } else if (++off == 2 && !radioState.equals("off")) {
                    Spotify.pause(radioState.equals("playing"));
                    radioState = "off";
                    radioTitle = "";
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private void loadArt(String url) {
        try {
            if (!url.startsWith("https://")) {
                artPending = null;
                return;
            }
            BufferedImage src = ImageIO.read(new URL(url));
            if (src == null) return;
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
            if (artTexture != 0 && !radioTitle.isEmpty()) {
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
            sub =
                    config.getProperty("radio.uri") == null
                            ? "Cole uma playlist no menu"
                            : radioState.equals("off") ? "Spotify fechado" : "Parado";
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
                    down = k > 0 && Keyboard.isKeyDown(k);
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
                case 180:
                    shot("antagon-smoke.png");
                    break;
                case 190:
                    openMenu();
                    break;
                case 210:
                    shot("antagon-menu.png");
                    break;
                case 212:
                    if (menu != null) menu.page = "cps";
                    break;
                case 230:
                    shot("antagon-options.png");
                    break;
                case 232:
                    if (menu != null) {
                        menu.page = null;
                        menu.editing = true;
                    }
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
                        Object self = field(mc, "field_71439_g", "thePlayer"),
                                ws =
                                        call(
                                                call(
                                                        mc,
                                                        new String[] {
                                                            "func_71401_C", "getIntegratedServer"
                                                        }),
                                                new String[] {
                                                    "func_71218_a", "worldServerForDimension"
                                                },
                                                0);
                        Object pig =
                                Class.forName("net.minecraft.entity.passive.EntityPig")
                                        .getConstructor(Class.forName("net.minecraft.world.World"))
                                        .newInstance(ws);
                        call(
                                pig,
                                new String[] {"func_70012_b", "setLocationAndAngles"},
                                d(self, "field_70165_t") + 1.5,
                                d(self, "field_70163_u"),
                                d(self, "field_70161_v") + 3.5,
                                0f,
                                0f);
                        call(ws, new String[] {"func_72838_d", "spawnEntityInWorld"}, pig);
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
                case 700:
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

    private void createFont() throws Exception {
        Font font;
        try (InputStream in = getClass().getResourceAsStream("/assets/antagon/PixelifySans.ttf")) {
            font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(20f);
        }
        BufferedImage atlas = new BufferedImage(ATLAS, ATLAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < 256; i++) {
            g.drawString("" + (char) i, (i % 16) * CELL, (i / 16) * CELL + 22);
            widths[i] = Math.min(30, fm.charWidth((char) i));
        }
        g.dispose();
        ByteBuffer pixels = BufferUtils.createByteBuffer(ATLAS * ATLAS * 4);
        for (int y = 0; y < ATLAS; y++)
            for (int x = 0; x < ATLAS; x++) {
                int p = atlas.getRGB(x, y);
                pixels.put((byte) (p >> 16))
                        .put((byte) (p >> 8))
                        .put((byte) p)
                        .put((byte) (p >> 24));
            }
        pixels.flip();
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                ATLAS,
                ATLAS,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                pixels);
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
        if (texture == 0) createFont();
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

    private float width(String s) {
        float w = 0;
        for (char c : s.toCharArray()) w += (widths[c < 256 ? c : 63] + 1) * .5f;
        return w;
    }

    private void text(String s, float x, float y, int c) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        color(c);
        GL11.glBegin(GL11.GL_QUADS);
        for (char ch : s.toCharArray()) {
            int n = ch < 256 ? ch : 63;
            float u = (n % 16) / 16f, v = (n / 16) / 16f;
            GL11.glTexCoord2f(u, v);
            GL11.glVertex2f(x, y);
            GL11.glTexCoord2f(u + 1 / 16f, v);
            GL11.glVertex2f(x + 16, y);
            GL11.glTexCoord2f(u + 1 / 16f, v + 1 / 16f);
            GL11.glVertex2f(x + 16, y + 16);
            GL11.glTexCoord2f(u, v + 1 / 16f);
            GL11.glVertex2f(x, y + 16);
            x += (widths[n] + 1) * .5f;
        }
        GL11.glEnd();
    }

    private float[] place(
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
        return new float[] {x, y};
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
                int cx = g[0] / 2,
                        cy = g[1] / 2,
                        len = Integer.parseInt(opt("crosshair", "size")),
                        t = Integer.parseInt(opt("crosshair", "thick")),
                        gap = Integer.parseInt(opt("crosshair", "gap"));
                String style = opt("crosshair", "style");
                boolean lines = !style.equals("ponto"), dot = !style.equals("cruz");
                List<float[]> parts = new ArrayList<float[]>();
                float half = t / 2;
                if (lines) {
                    parts.add(new float[] {cx - gap - len - half, cy - half, len, t});
                    parts.add(new float[] {cx + gap + t - half, cy - half, len, t});
                    parts.add(new float[] {cx - half, cy - gap - len - half, t, len});
                    parts.add(new float[] {cx - half, cy + gap + t - half, t, len});
                }
                if (dot) parts.add(new float[] {cx - half, cy - half, t, t});
                if (flag("crosshair", "outline"))
                    for (float[] r : parts)
                        rect(r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, 0xFF000000);
                for (float[] r : parts)
                    rect(r[0], r[1], r[2], r[3], colorOf(opt("crosshair", "color")));
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
                            left.size()
                                    + (flag("cps", "right") ? " | " + right.size() : "")
                                    + " CPS",
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
                if (enabled("clock"))
                    panel(
                            "clock",
                            new SimpleDateFormat(
                                            opt("clock", "format").equals("12h")
                                                    ? "hh:mm a"
                                                    : "HH:mm")
                                    .format(new Date()),
                            210,
                            12,
                            sw,
                            sh);
            } finally {
                end();
            }
        } catch (Throwable error) {
            report(error);
        }
    }

    private final class Menu extends GuiScreen {
        static final int PW = 446, PH = 226;
        String page = null, drag = null, resizing = null, notice = "";
        float resizeAnchorX, resizeAnchorY, resizeWidth, resizeHeight, resizeSize;
        int corner;
        String binding = null;
        boolean volumeDrag = false;
        long volumeSent = 0;
        float ms = 1;
        boolean editing = false;
        float x0, y0, dx, dy;

        Menu() {
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
                call(mc, new String[] {"func_147108_a", "displayGuiScreen"}, (Object) null);
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
                                : Math.min(
                                        1,
                                        Math.min(
                                                (field_146295_m - 8f) / ph(),
                                                (field_146294_l - 8f) / PW));
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
                rect(x0, y0, PW, ph(), 0xF21E1E1E);
                rect(x0, y0, PW, 24, 0xFF151515);
                rect(x0, y0, 4, 24, RED);
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

        private int ph() {
            return page == null ? PH : Math.max(160, 40 + options(page).length * 26);
        }

        private float[] card(int i) {
            return new float[] {x0 + 8 + (i % 3) * 146, y0 + 32 + (i / 3) * 32, 138, 26};
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

        private void drawHome(int mx, int my) {
            text("MODS", x0 + 12, y0 + 5, WHITE);
            button("EDITAR HUD", x0 + PW - 84, y0 + 4, 76, 16, mx, my, false);
            for (int i = 0; i < MODS.length; i++) {
                float[] c = card(i);
                boolean on = enabled(MODS[i]);
                rect(
                        c[0],
                        c[1],
                        c[2],
                        c[3],
                        in(mx, my, c[0], c[1], c[2], c[3]) ? 0xFF303030 : 0xFF262626);
                text(NAMES[i], c[0] + 8, c[1] + 5, on ? WHITE : GRAY);
                toggle(c[0] + c[2] - 30, c[1] + 8, on);
                if (options(MODS[i]).length > 0)
                    slidersIcon(
                            c[0] + c[2] - 48,
                            c[1] + 8,
                            in(mx, my, c[0] + c[2] - 54, c[1], 20, c[3]) ? WHITE : GRAY);
            }
        }

        private void drawOptions(int mx, int my) {
            button("<", x0 + 8, y0 + 4, 18, 16, mx, my, false);
            text(NAMES[Arrays.asList(MODS).indexOf(page)], x0 + 34, y0 + 5, WHITE);
            String[][] opts = options(page);
            for (int k = 0; k < opts.length; k++) {
                float ry = y0 + 32 + k * 26;
                String v = opt(page, opts[k][0]);
                rect(x0 + 8, ry, PW - 16, 22, 0xFF262626);
                text(opts[k][1], x0 + 16, ry + 3, WHITE);
                if (opts[k][0].equals("playlist")) {
                    String uri = config.getProperty("radio.uri");
                    text(
                            uri == null ? "NENHUMA" : uri.split(":")[1].toUpperCase(),
                            x0 + 90,
                            ry + 3,
                            GRAY);
                    button("COLAR LINK", x0 + PW - 108, ry + 3, 92, 16, mx, my, true);
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
                } else if (opts[k][2].equals("key")) {
                    boolean waiting = (page + "." + opts[k][0]).equals(binding);
                    button(
                            waiting ? "APERTE..." : v,
                            x0 + PW - 108,
                            ry + 3,
                            92,
                            16,
                            mx,
                            my,
                            waiting);
                } else if (opts[k][2].equals("slider")) {
                    int vol = radioVolume;
                    float sx = x0 + PW - 160;
                    rect(sx, ry + 10, 120, 2, 0xFF4A4A4A);
                    if (vol >= 0) {
                        rect(sx, ry + 10, 120 * vol / 100f, 2, RED);
                        rect(sx + 120 * vol / 100f - 3, ry + 6, 6, 10, WHITE);
                    }
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
            if (!editing || wheel == 0) return;
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
            int v = Math.max(0, Math.min(100, Math.round((mx - (x0 + PW - 160)) / 120f * 100)));
            radioVolume = v;
            volumeSentAt = System.currentTimeMillis();
            if (last || System.currentTimeMillis() - volumeSent > 150) {
                volumeSent = System.currentTimeMillis();
                Spotify.volume(v);
            }
        }

        private void pastePlaylist() {
            String uri = Spotify.uri(Spotify.clipboard());
            if (uri == null) {
                notice = "COPIE O LINK DE UMA PLAYLIST DO SPOTIFY";
                return;
            }
            notice = "";
            config.setProperty("radio.uri", uri);
            config.setProperty("radio", "true");
            save();
            radioPlay();
        }

        private void cycle(String mod, String[] o) {
            if (o == SIZE) {
                float now = percent(mod, "size");
                String next = null;
                for (String v : o[2].split(","))
                    if (Float.parseFloat(v.replace("%", "")) / 100f > now + .001f) {
                        next = v;
                        break;
                    }
                config.setProperty(mod + ".size", next == null ? o[2].split(",")[0] : next);
                save();
                return;
            }
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
                if (in(mx, my, x0 + PW - 84, y0 + 4, 76, 16)) {
                    editing = true;
                    return;
                }
                for (int i = 0; i < MODS.length; i++) {
                    float[] c = card(i);
                    if (options(MODS[i]).length > 0
                            && in(mx, my, c[0] + c[2] - 54, c[1], 20, c[3])) {
                        page = MODS[i];
                        return;
                    }
                    if (in(mx, my, c[0], c[1], c[2], c[3])) {
                        config.setProperty(MODS[i], "" + !enabled(MODS[i]));
                        save();
                        if (MODS[i].equals("radio") && enabled("radio")) radioPlay();
                        return;
                    }
                }
            } else {
                if (in(mx, my, x0 + 8, y0 + 4, 18, 16)) {
                    page = null;
                    return;
                }
                String[][] opts = options(page);
                for (int k = 0; k < opts.length; k++) {
                    float ry = y0 + 32 + k * 26;
                    if (opts[k][0].equals("playlist")) {
                        if (in(mx, my, x0 + PW - 108, ry + 3, 92, 16)) pastePlaylist();
                        continue;
                    }
                    if (opts[k][2].equals("key")) {
                        if (in(mx, my, x0 + PW - 108, ry + 3, 92, 16))
                            binding = page + "." + opts[k][0];
                        continue;
                    }
                    if (opts[k][2].equals("slider")) {
                        if (in(mx, my, x0 + PW - 164, ry, 128, 22)) {
                            volumeDrag = true;
                            setVolume(mx, false);
                        }
                        continue;
                    }
                    if (opts[k][0].equals("controls")) {
                        if (in(mx, my, x0 + PW - 108, ry + 3, 24, 16)) Spotify.previous();
                        else if (in(mx, my, x0 + PW - 82, ry + 3, 40, 16)) radioToggle();
                        else if (in(mx, my, x0 + PW - 40, ry + 3, 24, 16)) Spotify.next();
                        continue;
                    }
                    if (in(mx, my, x0 + PW - 108, ry, 92, 22)) {
                        cycle(page, opts[k]);
                        return;
                    }
                }
            }
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
            config.setProperty(drag + ".x", "" + Math.max(0, Math.round(mx / hudScale - dx)));
            config.setProperty(drag + ".y", "" + Math.max(0, Math.round(my / hudScale - dy)));
        }

        @Override
        protected void func_146286_b(int mx, int my, int state) {
            if (volumeDrag) {
                volumeDrag = false;
                setVolume(mx / ms, true);
            }
            if (drag != null || resizing != null) {
                drag = null;
                resizing = null;
                save();
            }
        }
    }
}
