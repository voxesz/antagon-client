package studio.antagon.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import static studio.antagon.inventory.ClickPlan.Stack;

public final class ClickPlanTest {
    private static final Stack E = Stack.EMPTY;
    private static Stack s(int key, int count) { return new Stack(key, count, limit(key)); }
    private static int limit(int key) { return key % 5 == 0 ? 1 : key % 5 == 1 ? 16 : 64; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Map<Integer, Integer> totals(List<Stack> slots, Stack cursor) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Stack s : slots) if (!s.empty()) counts.merge(s.key(), s.count(), Integer::sum);
        if (!cursor.empty()) counts.merge(cursor.key(), cursor.count(), Integer::sum);
        return counts;
    }
    // Independent vanilla left-click simulator: verifies every predicted state and conservation.
    private static List<Stack> replay(List<Stack> input, ClickPlan.Plan plan, int[] allowed) {
        List<Stack> slots = new ArrayList<>(input);
        Stack cursor = E;
        int cursorOrigin = -1;
        Set<Integer> valid = IntStream.of(allowed).boxed().collect(java.util.stream.Collectors.toSet());
        Map<Integer, Integer> before = totals(input, E);
        for (ClickPlan.Click click : plan.clicks()) {
            check(valid.contains(click.slot()), "Touched protected slot");
            check(new ClickPlan.State(slots, cursor).equals(click.before()), "Incorrect precondition");
            Stack slot = slots.get(click.slot());
            if (cursor.empty() && !slot.empty()) cursorOrigin = click.slot();
            if (cursor.empty()) { cursor = slot; slots.set(click.slot(), E); }
            else if (slot.empty()) { slots.set(click.slot(), cursor); cursor = E; }
            else if (slot.key() != cursor.key()) { slots.set(click.slot(), cursor); cursor = slot; }
            else {
                int count = Math.min(cursor.count(), slot.limit() - slot.count());
                slots.set(click.slot(), new Stack(slot.key(), slot.count() + count, slot.limit()));
                cursor = cursor.count() == count ? E : new Stack(cursor.key(), cursor.count() - count, cursor.limit());
            }
            check(new ClickPlan.State(slots, cursor).equals(click.after()), "Incorrect prediction");
            check(totals(slots, cursor).equals(before), "Lost or duplicated items");
            if (!cursor.empty()) {
                check(cursorOrigin >= 0 && slots.get(cursorOrigin).empty(), "No safe source for cancellation");
                List<Stack> cancelled = new ArrayList<>(slots);
                cancelled.set(cursorOrigin, cursor);
                check(totals(cancelled, E).equals(before), "Cancellation lost items");
            } else cursorOrigin = -1;
        }
        check(cursor.empty(), "Nonempty cursor at end");
        check(new ClickPlan.State(slots, cursor).equals(plan.result()), "Incorrect result");
        return slots;
    }
    public static void main(String[] args) {
        Random random = new Random(230326);
        int[] storage = IntStream.range(9, 36).toArray();
        int[] chest = IntStream.range(36, 90).toArray();
        int[] all = IntStream.range(9, 90).toArray();
        for (int round = 0; round < 1500; round++) {
            List<Stack> initial = new ArrayList<>();
            for (int i = 0; i < 90; i++) {
                int key = random.nextInt(14);
                initial.add(random.nextInt(4) == 0 ? E : s(key, 1 + random.nextInt(limit(key))));
            }
            ClickPlan.Plan sorted = ClickPlan.sort(initial, storage, Comparator.naturalOrder());
            List<Stack> result = replay(initial, sorted, storage);
            int previous = -1, count = 64; boolean sawEmpty = false;
            for (int index : storage) {
                Stack current = result.get(index);
                if (current.empty()) { sawEmpty = true; continue; }
                check(!sawEmpty, "Gap after sorting");
                check(current.key() >= previous, "Unsorted keys");
                if (current.key() == previous) check(count == current.limit(), "Unconsolidated stacks");
                previous = current.key(); count = current.count();
            }
            check(ClickPlan.sort(result, storage, Comparator.naturalOrder()).clicks().isEmpty(), "Sort not idempotent");
            Set<Integer> keys = ClickPlan.keys(initial, chest);
            ClickPlan.Plan deposit = ClickPlan.transfer(initial, storage, chest, keys);
            result = replay(initial, deposit, all);
            int moved = 0;
            for (int index : storage) {
                Stack a = initial.get(index), b = result.get(index);
                moved += a.count() - b.count();
                if (!a.empty() && !keys.contains(a.key())) check(a.equals(b), "Sent nonmatching item");
            }
            check(moved == deposit.transferred(), "Wrong transfer count");
            replay(initial, ClickPlan.transfer(initial, chest, storage, null), all);
            replay(initial, ClickPlan.transfer(initial, chest, storage, ClickPlan.keys(initial, storage)), all);
        }
        List<Stack> full = Arrays.asList(s(2, 64), s(3, 64));
        check(ClickPlan.transfer(full, new int[]{0}, new int[]{1}, null).clicks().isEmpty(), "Full destination changed");
        check(ClickPlan.sort(List.of(E, E), new int[]{0,1}, Comparator.naturalOrder()).clicks().isEmpty(), "Empty sort changed");
        List<Stack> matching = List.of(s(2, 50), s(3, 10), s(2, 60), E);
        ClickPlan.Plan partial = ClickPlan.transfer(matching, new int[]{0,1}, new int[]{2,3}, Set.of(2));
        List<Stack> transferred = replay(matching, partial, new int[]{0,1,2,3});
        check(partial.transferred() == 50 && transferred.get(2).count() == 64 && transferred.get(3).count() == 46,
            "Partial matching transfer failed");
        check(transferred.get(1).equals(matching.get(1)), "Transferred a nonmatching component variant");
        check(ClickPlan.transfer(matching, new int[]{0,1}, new int[]{2,3}, Set.of()).clicks().isEmpty(),
            "Deposited into a chest without matching items");
        try {
            ClickPlan.transfer(full, new int[]{0}, new int[]{0}, null);
            throw new AssertionError("Overlapping groups accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("OK: 1500 randomized inventories; sort, merging, transfers, full destinations, protected slots and item conservation.");
    }
}
