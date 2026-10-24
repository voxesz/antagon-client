package studio.antagon.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import studio.antagon.inventory.server.StorageTransfer;

/** Uses the actual Minecraft 26.3 containers and item components, without starting a game or opening a GUI. */
public final class StorageTransferTest {
    private static final int[] STORAGE = IntStream.range(9, 36).toArray();
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static List<ItemStack> snapshot(Container container) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) result.add(container.getItem(i).copy());
        return result;
    }
    private static void same(Container container, List<ItemStack> expected) {
        for (int i = 0; i < expected.size(); i++) check(ItemStack.matches(container.getItem(i), expected.get(i)), "Unexpected change in slot " + i);
    }
    private static void conserved(List<ItemStack> before, Container... after) {
        List<ItemStack> variants = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (ItemStack item : before) {
            if (item.isEmpty()) continue;
            int key = -1;
            for (int i = 0; i < variants.size(); i++)
                if (ItemStack.isSameItemSameComponents(variants.get(i), item)) { key = i; break; }
            if (key < 0) { key = variants.size(); variants.add(item.copyWithCount(1)); counts.add(0); }
            counts.set(key, counts.get(key) + item.getCount());
        }
        for (Container container : after) for (ItemStack item : snapshot(container)) {
            if (item.isEmpty()) continue;
            int key = -1;
            for (int i = 0; i < variants.size(); i++)
                if (ItemStack.isSameItemSameComponents(variants.get(i), item)) { key = i; break; }
            check(key >= 0, "Created an item or changed its components");
            counts.set(key, counts.get(key) - item.getCount());
        }
        check(counts.stream().allMatch(count -> count == 0), "Lost or duplicated items");
    }
    private static List<ItemStack> combined(Container first, Container second) {
        List<ItemStack> result = new ArrayList<>(snapshot(first)); result.addAll(snapshot(second)); return result;
    }
    private static final class FailingContainer extends SimpleContainer {
        int remaining = -1;
        FailingContainer() { super(27); }
        @Override public void setItem(int index, ItemStack item) {
            if (remaining > 0 && --remaining == 0) throw new IllegalStateException("Simulated container failure");
            super.setItem(index, item);
        }
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 26.3 binds default item components after the world registries are available.
        var registries = VanillaRegistries.createWorldLookup();
        for (var components : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)) components.apply();
        SimpleContainer source = new SimpleContainer(41);
        SimpleContainer chest = new SimpleContainer(27);
        source.setItem(0, new ItemStack(Items.STONE, 10));
        source.setItem(9, new ItemStack(Items.STONE, 40));
        source.setItem(10, new ItemStack(Items.STONE, 40));
        source.setItem(11, new ItemStack(Items.DIRT, 7));
        ItemStack named = new ItemStack(Items.STONE, 4);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Especial"));
        source.setItem(12, named);
        chest.setItem(0, new ItemStack(Items.STONE, 20));
        List<ItemStack> before = combined(source, chest);
        check(StorageTransfer.matching(source, STORAGE, chest) == 80, "Wrong direct transfer count");
        check(chest.getItem(0).getCount() == 64 && chest.getItem(1).getCount() == 36, "Partial stack merge failed");
        check(source.getItem(0).getCount() == 10 && source.getItem(11).getCount() == 7
            && ItemStack.matches(source.getItem(12), named), "Moved protected or nonmatching items");
        conserved(before, source, chest);
        List<ItemStack> saved = snapshot(source);
        check(StorageTransfer.matching(source, STORAGE, new SimpleContainer(27)) == 0, "Deposited into an empty chest");
        same(source, saved);

        FailingContainer failure = new FailingContainer();
        source.setItem(9, new ItemStack(Items.STONE, 50));
        failure.setItem(0, new ItemStack(Items.STONE, 60));
        List<ItemStack> originalSource = snapshot(source), originalDestination = snapshot(failure);
        failure.remaining = 2;
        try {
            StorageTransfer.matching(source, STORAGE, failure);
            throw new AssertionError("Simulated failure did not occur");
        } catch (IllegalStateException expected) { }
        same(source, originalSource); same(failure, originalDestination);

        Random random = new Random(263110);
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.set(DataComponents.DAMAGE, 20);
        List<ItemStack> types = List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT),
            new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.DIAMOND_PICKAXE), pickaxe, named.copyWithCount(1));
        for (int round = 0; round < 300; round++) {
            SimpleContainer inv = new SimpleContainer(41);
            Container target = round % 2 == 0 ? new SimpleContainer(27) : new CompoundContainer(new SimpleContainer(27), new SimpleContainer(27));
            for (Container c : new Container[]{inv, target}) for (int i = 0; i < c.getContainerSize(); i++) {
                if (random.nextInt(4) == 0) continue;
                ItemStack type = types.get(random.nextInt(types.size()));
                c.setItem(i, type.copyWithCount(1 + random.nextInt(type.getMaxStackSize())));
            }
            List<ItemStack> original = snapshot(inv), all = combined(inv, target);
            int oldCount = IntStream.of(STORAGE).map(i -> inv.getItem(i).getCount()).sum();
            int moved = StorageTransfer.matching(inv, STORAGE, target);
            check(oldCount - IntStream.of(STORAGE).map(i -> inv.getItem(i).getCount()).sum() == moved, "Incorrect moved count");
            for (int i = 0; i < 41; i++)
                if (i < 9 || i >= 36) check(ItemStack.matches(inv.getItem(i), original.get(i)), "Changed hotbar or equipment");
            conserved(all, inv, target);
        }
        System.out.println("OK: 300 real Minecraft inventories, double chests, components, hotbar protection, matching-only transfers and rollback.");
    }
}
