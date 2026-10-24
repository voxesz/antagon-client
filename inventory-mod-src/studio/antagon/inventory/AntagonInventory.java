package studio.antagon.inventory;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import studio.antagon.inventory.mixin.ContainerScreenAccessor;

public final class AntagonInventory implements ClientModInitializer {
    private static InventoryConfig config;
    public static InventoryConfig config() { return config; }
    public void onInitializeClient() {
        config = new InventoryConfig(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config/antagon-inventory.properties"));
        ClientTickEvents.END_CLIENT_TICK.register(InventoryActions::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!InventoryActions.supported(screen)) return;
            AbstractContainerScreen<?> container = (AbstractContainerScreen<?>)screen;
            boolean inventory = screen instanceof InventoryScreen;
            String[] actions = inventory ? new String[]{"sort_inventory", "deposit"} :
                new String[]{"take_matching", "take_all", "sort_chest"};
            Runnable[] callbacks = inventory ? new Runnable[]{
                () -> InventoryActions.sort(container, false), () -> InventoryActions.depositNearby(container)
            } : new Runnable[]{
                () -> InventoryActions.take(container, true), () -> InventoryActions.take(container, false),
                () -> InventoryActions.sort(container, true)
            };
            List<Button> buttons = new ArrayList<>();
            for (int i = 0; i < actions.length; i++) {
                String action = actions[i];
                Runnable callback = callbacks[i];
                Button button = new InventoryIconButton(action, callback);
                Screens.getWidgets(screen).add(button);
                buttons.add(button);
            }
            Runnable update = () -> {
                ContainerScreenAccessor bounds = (ContainerScreenAccessor)container;
                int top = bounds.antagonInventoryTop();
                int right = bounds.antagonInventoryLeft() + bounds.antagonInventoryWidth();
                for (int i = 0; i < buttons.size(); i++) {
                    Button b = buttons.get(i);
                    b.active = !InventoryActions.busy() && client.player != null && !client.player.isSpectator()
                        && container.getMenu().getCarried().isEmpty();
                    if (inventory) {
                        // Vanilla 26.3: the 20x18 recipe button is at left+104, height/2-22.
                        b.setX(bounds.antagonInventoryLeft() + 127 + i * 16);
                        b.setY(screen.height / 2 - 20);
                    } else {
                        b.setX(right - 7 - (buttons.size() - i) * 16);
                        b.setY(top + 2);
                    }
                }
            };
            update.run();
            ScreenEvents.afterTick(screen).register(s -> update.run());
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> DragMove.click(container, event));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> DragMove.release(container, event));
            ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> DragMove.drag(container, event));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, hx, vy) -> !InventoryActions.busy());
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!InventoryActions.busy()) return true;
                if (event.key() == InputConstants.KEY_ESCAPE || client.options.keyInventory.matches(event)) {
                    return InventoryActions.cancelFromKeyboard();
                }
                return false;
            });
        });
        System.out.println("[ANTAGON INVENTORY] Ícones do inventário prontos (26.3)");
    }
}
