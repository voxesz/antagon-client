package studio.antagon.inventory;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.InputWithModifiers;

/** Small vanilla-style button with an original pixel icon and a full accessible label. */
public final class InventoryIconButton extends Button {
    private final String[] icon;
    private final String action;

    public InventoryIconButton(String action, Runnable callback) {
        super(0, 0, 14, 14, Component.translatable("antagon_inventory.button." + action),
            button -> InventoryActions.perform(callback), DEFAULT_NARRATION);
        this.action = action;
        icon = switch (action) {
            case "sort_inventory", "sort_chest" -> new String[]{
                "......#.", "####..#.", "......#.", "###...#.", ".....###", "##....#.", "........", "........"
            };
            case "deposit" -> new String[]{
                "...#....", "..###...", ".#####..", "...#....", "...#....", "#......#", "#......#", "########"
            };
            case "take_matching" -> new String[]{
                "###..#..", ".....#..", "###..#..", "....###.", ".....#..", "........", "########", "........"
            };
            case "take_all" -> new String[]{
                ".#...#..", ".#...#..", ".#...#..", "###.###.", ".#...#..", "........", "########", "........"
            };
            default -> throw new IllegalArgumentException("Ação de inventário desconhecida");
        };
        setTooltip(Tooltip.create(Component.translatable("antagon_inventory.tooltip." + action,
            AntagonInventory.config().storageRadius())));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (action.equals("deposit") && input != null && input.hasShiftDown()) {
            var client = Minecraft.getInstance();
            client.gui.setScreen(new StorageSettingsScreen(client.gui.screen()));
        } else super.onPress(input);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractDefaultSprite(graphics);
        int color = active ? 0xFFFFFFFF : 0xFF777777;
        for (int y = 0; y < icon.length; y++)
            for (int x = 0; x < icon[y].length(); x++)
                if (icon[y].charAt(x) == '#')
                    graphics.fill(getX() + 3 + x, getY() + 3 + y, getX() + 4 + x, getY() + 4 + y, color);
    }
}
