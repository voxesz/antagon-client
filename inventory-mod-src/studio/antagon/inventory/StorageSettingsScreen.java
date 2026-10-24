package studio.antagon.inventory;

import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class StorageSettingsScreen extends Screen {
    private final Screen parent;
    private int radius;

    public StorageSettingsScreen(Screen parent) {
        super(Component.translatable("antagon_inventory.settings.title"));
        this.parent = parent;
        radius = AntagonInventory.config().storageRadius();
    }
    @Override
    protected void init() {
        addRenderableWidget(new AbstractSliderButton(width / 2 - 100, height / 2 - 12, 200, 20,
            Component.empty(), (radius - InventoryConfig.MIN_RADIUS) / (double)(InventoryConfig.MAX_RADIUS - InventoryConfig.MIN_RADIUS)) {
            { updateMessage(); }
            @Override protected void updateMessage() {
                setMessage(Component.translatable("antagon_inventory.settings.radius", radius));
            }
            @Override protected void applyValue() {
                radius = InventoryConfig.clamp((int)Math.round(value * (InventoryConfig.MAX_RADIUS - InventoryConfig.MIN_RADIUS)) + InventoryConfig.MIN_RADIUS);
            }
        });
        addRenderableWidget(Button.builder(Component.translatable("antagon_inventory.settings.done"), button -> onClose())
            .bounds(width / 2 - 50, height / 2 + 42, 100, 20).build());
    }
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        var font = Minecraft.getInstance().font;
        graphics.centeredText(font, title, width / 2, height / 2 - 44, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("antagon_inventory.settings.help"), width / 2, height / 2 + 16, 0xFFCCCCCC);
    }
    @Override
    public void onClose() {
        boolean saved = true;
        try { AntagonInventory.config().setStorageRadius(radius); }
        catch (IOException error) { saved = false; }
        Minecraft.getInstance().gui.setScreen(parent);
        if (!saved) InventoryActions.message("settings.save_failed");
    }
    @Override public boolean isPauseScreen() { return false; }
}
