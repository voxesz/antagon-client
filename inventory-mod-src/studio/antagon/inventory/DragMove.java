package studio.antagon.inventory;

import java.util.ArrayDeque;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import studio.antagon.inventory.mixin.ContainerScreenAccessor;

/** Hold left mouse and sweep the player's main inventory to quick-move into the open chest. */
public final class DragMove {
    private record Pending(int slot, ItemStack expected) { }
    private static DragMove gesture;
    private final AbstractContainerScreen<?> screen;
    private final DragGesture selection;
    private final ItemStack original;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private int delay;

    private DragMove(AbstractContainerScreen<?> screen, int origin) {
        this.screen = screen;
        original = screen.getMenu().getSlot(origin).getItem().copy();
        selection = new DragGesture(origin, screen.getMenu().slots.stream().filter(DragMove::playerSlot)
            .map(slot -> slot.index).collect(Collectors.toSet()));
    }
    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static boolean playerSlot(Slot slot) {
        return mc().player != null && slot.container == mc().player.getInventory()
            && slot.getContainerSlot() >= 9 && slot.getContainerSlot() < 36 && slot.isActive();
    }
    private static int hovered(AbstractContainerScreen<?> screen, double x, double y) {
        ContainerScreenAccessor bounds = (ContainerScreenAccessor)screen;
        for (Slot slot : screen.getMenu().slots) {
            int left = bounds.antagonInventoryLeft() + slot.x, top = bounds.antagonInventoryTop() + slot.y;
            if (playerSlot(slot) && x >= left && x < left + 16 && y >= top && y < top + 16) return slot.index;
        }
        return -1;
    }
    public static boolean busy() { return gesture != null; }
    public static void cancel() { gesture = null; }
    public static boolean click(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (InventoryActions.automated()) return false;
        if (gesture != null) return false;
        if (!(screen.getMenu() instanceof ChestMenu) || event.button() != 0 || event.modifiers() != 0
            || mc().player == null || mc().player.isSpectator() || !screen.getMenu().getCarried().isEmpty()) return true;
        int index = hovered(screen, event.x(), event.y());
        if (index < 0 || !screen.getMenu().getSlot(index).hasItem()) return true;
        gesture = new DragMove(screen, index);
        return false; // Wait to distinguish a normal click (release) from an inventory sweep.
    }
    public static boolean drag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (gesture == null) return !InventoryActions.automated();
        if (gesture.screen != screen || event.button() != 0) return false;
        gesture.selection.move(hovered(screen, event.x(), event.y()));
        for (int index : gesture.selection.drain()) {
            ItemStack item = screen.getMenu().getSlot(index).getItem();
            if (index == gesture.selection.origin() && !ItemStack.matches(item, gesture.original)) {
                gesture = null;
                return false;
            }
            if (!item.isEmpty()) gesture.queue.addLast(new Pending(index, item.copy()));
        }
        return false;
    }
    public static boolean release(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (gesture == null) return !InventoryActions.automated();
        if (gesture.screen != screen || event.button() != 0) return false;
        if (mc().player == null || mc().gameMode == null) { gesture = null; return false; }
        drag(screen, event);
        if (gesture == null) return false;
        DragMove current = gesture;
        if (current.selection.release()) {
            gesture = null;
            Slot slot = screen.getMenu().getSlot(current.selection.origin());
            if (mc().player.containerMenu == screen.getMenu() && screen.getMenu().getCarried().isEmpty()
                && ItemStack.matches(slot.getItem(), current.original) && slot.mayPickup(mc().player)) {
                mc().gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, 0, ContainerInput.PICKUP, mc().player);
            }
        } else if (current.queue.isEmpty()) gesture = null;
        return false;
    }
    public static void tick() {
        if (gesture == null) return;
        DragMove current = gesture;
        if (mc().player == null || mc().gameMode == null || mc().player.isSpectator()
            || mc().gui.screen() != current.screen || mc().player.containerMenu != current.screen.getMenu()
            || !current.screen.getMenu().getCarried().isEmpty()) { gesture = null; return; }
        if (current.delay-- > 0) return;
        Pending next = current.queue.pollFirst();
        if (next != null) {
            Slot slot = current.screen.getMenu().getSlot(next.slot());
            // Never move a replacement item that appeared after the pointer passed this slot.
            if (ItemStack.matches(slot.getItem(), next.expected()) && slot.mayPickup(mc().player))
                mc().gameMode.handleContainerInput(current.screen.getMenu().containerId, slot.index, 0,
                    ContainerInput.QUICK_MOVE, mc().player);
            current.delay = 1;
        }
        if (!current.selection.holding() && current.queue.isEmpty()) gesture = null;
    }
    private DragMove() { throw new AssertionError(); }
}
