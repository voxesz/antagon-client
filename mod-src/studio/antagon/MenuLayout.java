package studio.antagon;

final class MenuLayout {
    static final int WIDTH = 546, CARD_HEIGHT = 62, ROW_HEIGHT = 70;
    static final int CONTENT_TOP = 82, FOOTER = 28, PADDING = 16;

    private MenuLayout() {}

    static float scale(int screenWidth, int screenHeight, int contentHeight) {
        float scale = Math.min(1, Math.max(1, screenWidth - 24) / (float) WIDTH);
        return contentHeight == 0
                ? scale
                : Math.min(scale, Math.max(1, screenHeight - 24) / (float) contentHeight);
    }

    static int homeHeight(int screenWidth, int screenHeight) {
        return Math.max(
                1,
                Math.min(416, (int) ((screenHeight - 24) / scale(screenWidth, screenHeight, 0))));
    }

    static float viewport(int height) {
        return Math.max(1, height - CONTENT_TOP - FOOTER);
    }

    static float maxScroll(int count, int height) {
        int rows = (count + 2) / 3;
        return Math.max(0, rows * ROW_HEIGHT - (ROW_HEIGHT - CARD_HEIGHT) - viewport(height));
    }

    static float clampScroll(float scroll, int count, int height) {
        return Math.max(0, Math.min(scroll, maxScroll(count, height)));
    }

    static float[] card(int index, float x, float y, float scroll) {
        float width = (WIDTH - PADDING * 2 - 16) / 3f;
        return new float[] {
            x + PADDING + index % 3 * (width + 8),
            y + CONTENT_TOP + index / 3 * ROW_HEIGHT - scroll,
            width,
            CARD_HEIGHT
        };
    }
}
