package studio.antagon;

import static studio.antagon.Reflect.*;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

import javax.imageio.ImageIO;

/** Reskins the original screens: native buttons still handle navigation and game actions. */
final class ClientMenus {
    private final PixelFont font = new PixelFont();
    private final Map<String, Integer> textures = new HashMap<String, Integer>();
    private final Map<Integer, Float> hover = new HashMap<Integer, Float>();
    private final List<float[]> sparks = new ArrayList<float[]>();
    private final Random random = new Random(71);
    private float scale = 1, logoY, pointerX, pointerY, lastX = -1, lastY = -1;
    private long frame;
    private Object current;
    private static final int RED = 0xFFEE1515, WHITE = 0xFFF0EEE8;

    static boolean supports(Object gui) {
        String name = gui.getClass().getName();
        return name.equals("net.minecraft.client.gui.GuiMainMenu")
                || name.equals("net.minecraft.client.gui.GuiIngameMenu");
    }

    void layout(
            Object gui,
            List<Object> buttons,
            boolean paused,
            int settings,
            int friends,
            int store,
            int admin,
            int radio)
            throws Exception {
        int width = ((Number) field(gui, "field_146294_l", "width")).intValue();
        int height = ((Number) field(gui, "field_146295_m", "height")).intValue();
        Map<Integer, Object> left = new LinkedHashMap<Integer, Object>();
        for (Object button : buttons) left.put(id(button), button);
        List<List<Object>> rows = new ArrayList<List<Object>>();
        if (paused) {
            row(rows, left, 4);
            row(rows, left, 5, 6);
            row(rows, left, 0, settings);
            row(rows, left, friends, store, radio, admin);
            row(rows, left, 7);
            row(rows, left, 12);
        } else {
            row(rows, left, 1);
            row(rows, left, 2);
            row(rows, left, 14);
            row(rows, left, 0, settings);
            row(rows, left, 6, 5);
            row(rows, left, friends, store, radio, admin);
        }
        Object quit = left.remove(paused ? 1 : 4);
        for (Object button : left.values()) rows.add(Collections.singletonList(button));
        if (quit != null) rows.add(Collections.singletonList(quit));
        float total = 94 + rows.size() * 26;
        scale = Math.min(1f, Math.min((width - 40) / 310f, (height - 42) / total));
        scale = Math.max(.45f, scale);
        float top = (height - total * scale) / 2;
        logoY = top;
        int buttonWidth = Math.round(310 * scale), buttonHeight = Math.round(22 * scale);
        int startX = (width - buttonWidth) / 2;
        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            boolean icons = id(row.get(0)) == friends;
            int gap = Math.max(3, Math.round(5 * scale));
            int w = icons ? buttonHeight : (buttonWidth - gap * (row.size() - 1)) / row.size();
            int x = icons ? (width - row.size() * w - gap * (row.size() - 1)) / 2 : startX;
            int y = Math.round(top + (94 + i * 26) * scale);
            for (Object button : row) {
                setField(button, x, "field_146128_h", "xPosition");
                setField(button, y, "field_146129_i", "yPosition");
                setField(button, w, "field_146120_f", "width");
                setField(button, buttonHeight, "field_146121_g", "height");
                x += w + gap;
            }
        }
        current = gui;
        hover.clear();
        sparks.clear();
        lastX = lastY = -1;
        frame = 0;
    }

    private static void row(List<List<Object>> rows, Map<Integer, Object> left, int... ids) {
        List<Object> row = new ArrayList<Object>();
        for (int id : ids) {
            Object button = left.remove(id);
            if (button != null) row.add(button);
        }
        if (!row.isEmpty()) rows.add(row);
    }

    private static int id(Object button) throws Exception {
        return ((Number) field(button, "field_146127_k", "id")).intValue();
    }

    void draw(
            Object gui,
            List<Object> buttons,
            int mx,
            int my,
            boolean paused,
            int settings,
            int friends,
            int store,
            int admin,
            int radio)
            throws Exception {
        if (gui != current) layout(gui, buttons, paused, settings, friends, store, admin, radio);
        int width = ((Number) field(gui, "field_146294_l", "width")).intValue();
        int height = ((Number) field(gui, "field_146295_m", "height")).intValue();
        long now = System.nanoTime();
        float dt = frame == 0 ? 1 / 60f : Math.min(.05f, (now - frame) / 1e9f);
        frame = now;
        float targetX = Math.max(-1, Math.min(1, mx * 2f / width - 1));
        float targetY = Math.max(-1, Math.min(1, my * 2f / height - 1));
        float follow = 1 - (float) Math.exp(-dt * 8);
        pointerX += (targetX - pointerX) * follow;
        pointerY += (targetY - pointerY) * follow;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            font.prepare();
            rect(0, 0, width, height, paused ? 0xF4101116 : 0xFF101116);
            // A soft red glow and short particle trail follow movement, with a fixed upper bound.
            for (int i = 8; i > 0; i--) {
                float r = 14 + i * 7;
                disc(mx, my, r, 0x02EE1515);
            }
            if (lastX >= 0 && Math.abs(mx - lastX) + Math.abs(my - lastY) > 1) {
                sparks.add(
                        new float[] {
                            mx, my, 0, (random.nextFloat() - .5f) * 16, -8 - random.nextFloat() * 12
                        });
                if (sparks.size() > 40) sparks.remove(0);
            }
            lastX = mx;
            lastY = my;
            for (Iterator<float[]> it = sparks.iterator(); it.hasNext(); ) {
                float[] p = it.next();
                p[2] += dt;
                if (p[2] > .7f) {
                    it.remove();
                    continue;
                }
                p[0] += p[3] * dt;
                p[1] += p[4] * dt;
                int alpha = (int) (110 * (1 - p[2] / .7f));
                rect(p[0], p[1], 1.5f, 1.5f, (alpha << 24) | 0xEE1515);
            }
            image("logo", 14, 12, 15, 15, WHITE);
            label("ANTAGON", 35, 13, .7f, WHITE);
            GL11.glPushMatrix();
            GL11.glTranslatef(
                    width / 2f + pointerX * 6 * scale,
                    logoY + 34 * scale + pointerY * 4 * scale,
                    0);
            GL11.glRotatef(-pointerY * 9, 1, 0, 0);
            GL11.glRotatef(pointerX * 12, 0, 1, 0);
            float size = 66 * scale;
            for (int i = 5; i > 0; i--)
                image("logo", -size / 2 + i * scale, -size / 2 + i * scale, size, size, 0xFF5D1016);
            image("logo", -size / 2, -size / 2, size, size, WHITE);
            GL11.glPopMatrix();
            String title = paused ? "JOGO PAUSADO" : "MINECRAFT";
            label(
                    title,
                    (width - font.width(title) * .8f * scale) / 2,
                    logoY + 73 * scale,
                    .8f * scale,
                    WHITE);
            String tooltip = null;
            for (Object button : buttons) {
                if (!Boolean.TRUE.equals(field(button, "field_146125_m", "visible"))) continue;
                int id = id(button);
                float x = ((Number) field(button, "field_146128_h", "xPosition")).floatValue();
                float y = ((Number) field(button, "field_146129_i", "yPosition")).floatValue();
                float w = ((Number) field(button, "field_146120_f", "width")).floatValue();
                float h = ((Number) field(button, "field_146121_g", "height")).floatValue();
                boolean enabled = Boolean.TRUE.equals(field(button, "field_146124_l", "enabled"));
                boolean over = enabled && mx >= x && my >= y && mx < x + w && my < y + h;
                float old = hover.containsKey(id) ? hover.get(id) : 0;
                float amount = old + ((over ? 1 : 0) - old) * (1 - (float) Math.exp(-dt * 20));
                hover.put(id, amount);
                if (amount > .01f) rect(x, y, w, h, ((int) (amount * 255) << 24) | 0xEE1515);
                int edge = enabled ? (amount > .5 ? RED : 0xFF696B76) : 0xFF32343D;
                rect(x, y, w, 1, edge);
                rect(x, y + h - 1, w, 1, edge);
                rect(x, y, 1, h, edge);
                rect(x + w - 1, y, 1, h, edge);
                String icon =
                        id == friends
                                ? "chat"
                                : id == store ? "store" : id == admin ? "admin" : null;
                if (id == radio) {
                    float cx = x + w / 2, cy = y + h / 2;
                    rect(cx, cy - 5 * scale, 2 * scale, 9 * scale, WHITE);
                    rect(cx, cy - 5 * scale, 5 * scale, 2 * scale, WHITE);
                    disc(cx - 1.5f * scale, cy + 4 * scale, 3 * scale, WHITE);
                    if (over) tooltip = "Rádio";
                } else if (icon != null) {
                    float iconSize = h - 6 * scale;
                    image(
                            icon,
                            x + (w - iconSize) / 2,
                            y + (h - iconSize) / 2,
                            iconSize,
                            iconSize,
                            WHITE);
                    if (over) tooltip = id == friends ? "Amigos" : id == store ? "Loja" : "Admin";
                } else {
                    String text =
                            id == settings
                                    ? "OPÇÕES ANTAGON"
                                    : !paused && id == 5
                                            ? "IDIOMA"
                                            : String.valueOf(
                                                            field(
                                                                    button,
                                                                    "field_146126_j",
                                                                    "displayString"))
                                                    .toUpperCase(Locale.ROOT);
                    float fs = Math.min(.85f * scale, (w - 10) / Math.max(1, font.width(text)));
                    label(
                            text,
                            x + (w - font.width(text) * fs) / 2,
                            y + (h - 15 * fs) / 2,
                            fs,
                            enabled ? WHITE : 0xFF656772);
                }
            }
            label("Minecraft 1.8.9", 12, height - 17, .6f, 0xFF797B85);
            if (tooltip != null) {
                float tw = font.width(tooltip) * .7f;
                float tx = Math.min(width - tw - 8, mx + 8), ty = Math.max(0, my - 20);
                rect(tx - 4, ty - 2, tw + 8, 15, 0xEE111216);
                label(tooltip, tx, ty, .7f, WHITE);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void label(String text, float x, float y, float size, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(size, size, 1);
        font.draw(text, 0, 0, color);
        GL11.glPopMatrix();
    }

    private void image(String name, float x, float y, float w, float h, int tint) throws Exception {
        Integer texture = textures.get(name);
        if (texture == null) {
            BufferedImage image;
            try (InputStream stream =
                    getClass().getResourceAsStream("/assets/antagon/" + name + ".png")) {
                image = ImageIO.read(stream);
            }
            ByteBuffer pixels =
                    BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);
            for (int iy = 0; iy < image.getHeight(); iy++)
                for (int ix = 0; ix < image.getWidth(); ix++) {
                    int p = image.getRGB(ix, iy);
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
                    image.getWidth(),
                    image.getHeight(),
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    pixels);
            textures.put(name, texture);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        color(tint);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(1, 0);
        GL11.glVertex2f(x + w, y);
        GL11.glTexCoord2f(1, 1);
        GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(0, 1);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private static void color(int c) {
        GL11.glColor4f(
                (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, (c >>> 24) / 255f);
    }

    private static void rect(float x, float y, float w, float h, int c) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        color(c);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private static void disc(float x, float y, float radius, int c) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        color(c);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(x, y);
        for (int i = 0; i <= 32; i++) {
            double angle = i * Math.PI / 16;
            GL11.glVertex2f(
                    x + (float) Math.cos(angle) * radius, y + (float) Math.sin(angle) * radius);
        }
        GL11.glEnd();
    }
}
