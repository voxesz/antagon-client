package studio.antagon.inventory.server;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import studio.antagon.inventory.ClickPlan;

/** Plans first, then commits on the server thread. Does not open a menu or use a cursor. */
public final class StorageTransfer {
    public static int matching(Container source, int[] sourceSlots, Container destination) {
        if (source == destination) throw new IllegalArgumentException("Origem e destino são iguais");
        if (IntStream.of(sourceSlots).distinct().count() != sourceSlots.length)
            throw new IllegalArgumentException("Espaços de origem repetidos");
        for (int index : sourceSlots)
            if (index < 0 || index >= source.getContainerSize()) throw new IllegalArgumentException("Espaço de origem inválido");
        List<ItemStack> original = new ArrayList<>();
        for (int index : sourceSlots) original.add(source.getItem(index).copy());
        for (int i = 0; i < destination.getContainerSize(); i++) original.add(destination.getItem(i).copy());
        List<ItemStack> variants = new ArrayList<>();
        List<ClickPlan.Stack> stacks = new ArrayList<>();
        for (ItemStack stack : original) {
            if (stack.isEmpty()) { stacks.add(ClickPlan.Stack.EMPTY); continue; }
            int key = -1;
            for (int i = 0; i < variants.size(); i++)
                if (ItemStack.isSameItemSameComponents(stack, variants.get(i))) { key = i; break; }
            if (key < 0) { key = variants.size(); variants.add(stack.copyWithCount(1)); }
            stacks.add(new ClickPlan.Stack(key, stack.getCount(), stack.getMaxStackSize()));
        }
        int count = sourceSlots.length;
        int[] from = IntStream.range(0, count).toArray();
        int[] to = IntStream.range(count, stacks.size()).toArray();
        ClickPlan.Plan plan = ClickPlan.transfer(stacks, from, to, ClickPlan.keys(stacks, to));
        if (plan.transferred() == 0) return 0;
        List<ItemStack> result = new ArrayList<>();
        for (ClickPlan.Stack stack : plan.result().slots())
            result.add(stack.empty() ? ItemStack.EMPTY : variants.get(stack.key()).copyWithCount(stack.count()));
        // Validate every destination before touching the world, including containers with custom slot rules.
        for (int i = 0; i < to.length; i++) {
            ItemStack item = result.get(count + i);
            if (!item.isEmpty() && (!destination.canPlaceItem(i, item) || item.getCount() > destination.getMaxStackSize(item)))
                return 0;
        }
        for (int i = 0; i < count; i++)
            if (!ItemStack.matches(source.getItem(sourceSlots[i]), original.get(i))) return 0;
        for (int i = 0; i < to.length; i++)
            if (!ItemStack.matches(destination.getItem(i), original.get(count + i))) return 0;
        List<Integer> written = new ArrayList<>();
        try {
            // Both containers are modified within one server task; other players and hoppers cannot interleave.
            for (int i : IntStream.concat(IntStream.range(count, result.size()), IntStream.range(0, count)).toArray()) {
                if (ItemStack.matches(original.get(i), result.get(i))) continue;
                written.add(i);
                if (i < count) source.setItem(sourceSlots[i], result.get(i).copy());
                else destination.setItem(i - count, result.get(i).copy());
            }
            for (int i = 0; i < count; i++)
                if (!ItemStack.matches(source.getItem(sourceSlots[i]), result.get(i))) throw new IllegalStateException("A origem recusou a transferência");
            for (int i = 0; i < to.length; i++)
                if (!ItemStack.matches(destination.getItem(i), result.get(count + i))) throw new IllegalStateException("O destino recusou a transferência");
            source.setChanged();
            destination.setChanged();
        } catch (RuntimeException error) {
            for (int j = written.size() - 1; j >= 0; j--) {
                int i = written.get(j);
                try {
                    if (i < count) source.setItem(sourceSlots[i], original.get(i).copy());
                    else destination.setItem(i - count, original.get(i).copy());
                } catch (RuntimeException rollback) { error.addSuppressed(rollback); }
            }
            throw error;
        }
        return plan.transferred();
    }
    private StorageTransfer() { }
}
