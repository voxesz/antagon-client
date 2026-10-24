package studio.antagon.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import studio.antagon.inventory.server.HostStorage;

/** Client controls and the world owner's integrated-server storage bridge. */
public final class InventoryActions {
    private static Job job;
    private static HostRequest hostRequest;
    private static long tick;
    private static Minecraft mc() { return Minecraft.getInstance(); }
    public static boolean automated() { return job != null || hostRequest != null; }
    public static boolean busy() { return automated() || DragMove.busy(); }
    public static void perform(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) {
            failed();
            System.err.println("[ANTAGON INVENTORY] Operação interrompida: " + error);
        }
    }
    public static void message(String key, Object... args) {
        if (mc().player != null) mc().player.sendOverlayMessage(Component.translatable("antagon_inventory." + key, args));
    }
    public static boolean supported(Screen screen) {
        return screen instanceof InventoryScreen ||
            screen instanceof AbstractContainerScreen<?> container && container.getMenu() instanceof ChestMenu;
    }
    private static boolean ready(AbstractContainerScreen<?> screen) {
        if (busy() || mc().player == null || mc().gameMode == null || mc().player.isSpectator()) return false;
        if (mc().player.containerMenu != screen.getMenu()) return false;
        if (!screen.getMenu().getCarried().isEmpty()) { message("empty_cursor"); return false; }
        return true;
    }
    public static void sort(AbstractContainerScreen<?> screen, boolean chest) {
        if (!ready(screen)) return;
        Session s = new Session(screen);
        int[] region = chest ? s.chest() : s.storage();
        run(s, ClickPlan.sort(s.state().slots(), region, s.order()), n -> message("sorted"));
    }
    public static void take(AbstractContainerScreen<?> screen, boolean matching) {
        if (!ready(screen)) return;
        Session s = new Session(screen);
        Set<Integer> eligible = matching ? ClickPlan.keys(s.state().slots(), s.playerItems()) : null;
        run(s, ClickPlan.transfer(s.state().slots(), s.chest(), s.storage(), eligible),
            n -> message(n == 0 ? "nothing_taken" : "taken", n));
    }
    private static void run(Session session, ClickPlan.Plan plan, IntConsumer finished) {
        if (plan.clicks().isEmpty()) { finished.accept(0); return; }
        job = new Job(session, plan, finished);
    }
    public static void cancel() {
        if (!busy()) return;
        DragMove.cancel();
        if (hostRequest != null) hostRequest.cancelled.set(true);
        job = null;
        hostRequest = null;
        message("cancelled");
    }
    /** Delay closing only long enough to put a carried stack back in its empty source slot. */
    public static boolean cancelFromKeyboard() {
        if (job != null && !job.session.menu.getCarried().isEmpty()) {
            job.cancelRequested = true;
            message("stopping");
            return false;
        }
        cancel();
        return true;
    }
    private static void failed() {
        DragMove.cancel();
        if (hostRequest != null) hostRequest.cancelled.set(true);
        job = null;
        hostRequest = null;
        // Keep the GUI/cursor visible. Closing a full inventory could drop a carried stack.
        message("changed");
    }
    public static void tick(Minecraft client) {
        tick++;
        if (client.player == null || client.level == null || client.gameMode == null) {
            DragMove.cancel();
            job = null;
            if (hostRequest != null) hostRequest.cancelled.set(true);
            hostRequest = null;
            return;
        }
        if (client.player.isDeadOrDying() || client.player.isSpectator()) { cancel(); return; }
        perform(() -> {
            DragMove.tick();
            if (job != null) job.tick();
            if (hostRequest != null) hostRequest.tick();
        });
    }

    private static final class Session {
        final AbstractContainerScreen<?> screen;
        final AbstractContainerMenu menu;
        final List<ItemStack> variants = new ArrayList<>();
        Session(AbstractContainerScreen<?> screen) { this.screen = screen; menu = screen.getMenu(); }
        int key(ItemStack stack) {
            for (int i = 0; i < variants.size(); i++)
                if (ItemStack.isSameItemSameComponents(variants.get(i), stack)) return i;
            variants.add(stack.copyWithCount(1));
            return variants.size() - 1;
        }
        ClickPlan.Stack stack(ItemStack stack) {
            return stack.isEmpty() ? ClickPlan.Stack.EMPTY :
                new ClickPlan.Stack(key(stack), stack.getCount(), stack.getMaxStackSize());
        }
        ClickPlan.State state() {
            List<ClickPlan.Stack> items = new ArrayList<>();
            for (Slot slot : menu.slots) items.add(stack(slot.getItem()));
            return new ClickPlan.State(items, stack(menu.getCarried()));
        }
        int[] storage() {
            return menu.slots.stream().filter(slot -> slot.container == mc().player.getInventory()
                && slot.getContainerSlot() >= 9 && slot.getContainerSlot() < 36)
                .sorted(Comparator.comparingInt(Slot::getContainerSlot)).mapToInt(slot -> slot.index).toArray();
        }
        int[] playerItems() {
            return menu.slots.stream().filter(slot -> slot.container == mc().player.getInventory()
                && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < 36)
                .mapToInt(slot -> slot.index).toArray();
        }
        int[] chest() {
            return menu instanceof ChestMenu chest ? IntStream.range(0, chest.getRowCount() * 9).toArray() : new int[0];
        }
        Comparator<Integer> order() {
            return Comparator.comparing((Integer key) -> variants.get(key).getHoverName().getString().toLowerCase(Locale.ROOT))
                .thenComparing(key -> BuiltInRegistries.ITEM.getKey(variants.get(key).getItem()).toString());
        }
    }
    private static final class Job {
        final Session session;
        final ClickPlan.Plan plan;
        final IntConsumer finished;
        int index;
        int cursorOrigin = -1;
        boolean cancelRequested;
        long nextTick = tick + 1;
        Job(Session session, ClickPlan.Plan plan, IntConsumer finished) {
            this.session = session; this.plan = plan; this.finished = finished;
        }
        void tick() {
            if (mc().player.containerMenu != session.menu || mc().gui.screen() != session.screen) { cancel(); return; }
            if (tick < nextTick) return;
            if (tick % 40 == 0) message("working");
            ClickPlan.State expected = index < plan.clicks().size() ? plan.clicks().get(index).before() : plan.result();
            if (!session.state().equals(expected)) { failed(); return; }
            if (cancelRequested) {
                if (!session.menu.getCarried().isEmpty()) {
                    if (cursorOrigin < 0) { failed(); return; }
                    Slot origin = session.menu.getSlot(cursorOrigin);
                    if (origin.hasItem() || !origin.mayPlace(session.menu.getCarried())) { failed(); return; }
                    mc().gameMode.handleContainerInput(session.menu.containerId, cursorOrigin, 0, ContainerInput.PICKUP, mc().player);
                    if (!session.menu.getCarried().isEmpty()) { failed(); return; }
                }
                cancel();
                mc().player.closeContainer();
                return;
            }
            if (index == plan.clicks().size()) {
                job = null;
                finished.accept(plan.transferred());
                return;
            }
            ClickPlan.Click step = plan.clicks().get(index);
            Slot slot = session.menu.getSlot(step.slot());
            ItemStack carried = session.menu.getCarried();
            if (!slot.isActive() || !slot.mayPickup(mc().player)
                || (!carried.isEmpty() && !slot.mayPlace(carried))) { failed(); return; }
            mc().gameMode.handleContainerInput(session.menu.containerId, step.slot(), 0, ContainerInput.PICKUP, mc().player);
            if (!session.state().equals(step.after())) { failed(); return; }
            if (step.before().cursor().empty() && !step.after().cursor().empty()) cursorOrigin = step.slot();
            if (step.after().cursor().empty()) cursorOrigin = -1;
            index++;
            // Pace real clicks; server corrections are checked before every subsequent click.
            nextTick = tick + (index == plan.clicks().size() ? 10 : 2);
        }
    }

    public static void depositNearby(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof InventoryScreen) || !ready(screen)) return;
        var server = mc().getSingleplayerServer();
        if (server == null) { message("host_only"); return; }
        Session session = new Session(screen);
        if (ClickPlan.keys(session.state().slots(), session.storage()).isEmpty()) {
            message("nothing_to_send"); return;
        }
        HostRequest request = new HostRequest(mc().level, mc().player.getUUID());
        hostRequest = request;
        var dimension = mc().level.dimension();
        message("sending_host");
        server.execute(() -> {
            try {
                HostStorage.Result result = HostStorage.deposit(server, request.owner, dimension, request.radius, request.cancelled);
                mc().execute(() -> {
                    if (hostRequest == request) {
                        request.result = result;
                        request.readyAt = tick + 5;
                    }
                });
            } catch (RuntimeException error) {
                System.err.println("[ANTAGON INVENTORY] Transferência do anfitrião interrompida: " + error);
                mc().execute(() -> { if (hostRequest == request) failed(); });
            }
        });
    }
    private static final class HostRequest {
        final Object level;
        final UUID owner;
        final int radius = AntagonInventory.config().storageRadius();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long timeout = tick + 200;
        long readyAt;
        HostStorage.Result result;
        HostRequest(Object level, UUID owner) { this.level = level; this.owner = owner; }
        void tick() {
            if (mc().level != level || !mc().player.getUUID().equals(owner)) { cancel(); return; }
            if (tick > timeout) { cancelled.set(true); hostRequest = null; message("host_timeout"); return; }
            if (result != null && tick >= readyAt) {
                hostRequest = null;
                message(result.status(), result.moved());
            }
        }
    }
    private InventoryActions() { }
}
