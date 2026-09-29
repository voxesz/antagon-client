package studio.antagon;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

final class PixelFont {
    private static final int CELL = 32, ATLAS = 512;
    private final int[] widths = new int[256];
    private int texture;

    void prepare() throws Exception {
        if (texture != 0) return;
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

    float width(String s) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (widths[c < 256 ? c : 63] + 1) * .5f;
        }
        return w;
    }

    void draw(String s, float x, float y, int c) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glColor4f(
                (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, (c >>> 24) / 255f);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
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
}
