package studio.antagon.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plans ordinary left clicks. Keys identify items AND their complete components. */
public final class ClickPlan {
    public record Stack(int key, int count, int limit) {
        public static final Stack EMPTY = new Stack(-1, 0, 64);
        public Stack {
            if (count < 0 || limit < 1 || count > limit || (count == 0) != (key == -1))
                throw new IllegalArgumentException("Pilha de itens inválida");
        }
        public boolean empty() { return count == 0; }
        public Stack withCount(int amount) { return amount == 0 ? EMPTY : new Stack(key, amount, limit); }
    }
    public record State(List<Stack> slots, Stack cursor) {
        public State { slots = List.copyOf(slots); }
    }
    public record Click(int slot, State before, State after) {}
    public record Plan(List<Click> clicks, State result, int transferred) {
        public Plan { clicks = List.copyOf(clicks); }
    }

    private final Stack[] slots;
    private Stack cursor = Stack.EMPTY;
    private final List<Click> clicks = new ArrayList<>();

    private ClickPlan(List<Stack> initial) { slots = initial.toArray(Stack[]::new); }
    private State state() { return new State(Arrays.asList(slots), cursor); }
    private void click(int index) {
        State before = state();
        Stack target = slots[index];
        if (cursor.empty()) {
            cursor = target;
            slots[index] = Stack.EMPTY;
        } else if (target.empty()) {
            slots[index] = cursor;
            cursor = Stack.EMPTY;
        } else if (cursor.key == target.key) {
            int moved = Math.min(cursor.count, target.limit - target.count);
            slots[index] = target.withCount(target.count + moved);
            cursor = cursor.withCount(cursor.count - moved);
        } else {
            slots[index] = cursor;
            cursor = target;
        }
        State after = state();
        if (!before.equals(after)) clicks.add(new Click(index, before, after));
    }
    private Plan finish(int transferred) {
        if (!cursor.empty()) throw new IllegalStateException("A operação deixou um item no cursor");
        return new Plan(clicks, state(), transferred);
    }
    private static void validate(List<Stack> stacks, int[]... groups) {
        Set<Integer> seen = new HashSet<>();
        for (int[] group : groups) for (int index : group)
            if (index < 0 || index >= stacks.size() || !seen.add(index))
                throw new IllegalArgumentException("Grupos de espaços inválidos ou sobrepostos");
    }

    public static Plan sort(List<Stack> initial, int[] region, Comparator<Integer> order) {
        validate(initial, region);
        ClickPlan p = new ClickPlan(initial);
        // Consolidate before ordering, without ever using a slot outside the requested region.
        for (int a = 0; a < region.length; a++) {
            int i = region[a];
            if (p.slots[i].empty()) continue;
            for (int b = a + 1; b < region.length && p.slots[i].count < p.slots[i].limit; b++) {
                int j = region[b];
                if (!p.slots[j].empty() && p.slots[i].key == p.slots[j].key) {
                    p.click(j);
                    p.click(i);
                    if (!p.cursor.empty()) p.click(j);
                }
            }
        }
        Comparator<Stack> compare = (a, b) -> {
            if (a.empty() || b.empty()) return Boolean.compare(a.empty(), b.empty());
            int c = order.compare(a.key, b.key);
            if (c == 0) c = Integer.compare(a.key, b.key);
            return c == 0 ? Integer.compare(b.count, a.count) : c;
        };
        for (int a = 0; a < region.length; a++) {
            int best = a;
            for (int b = a + 1; b < region.length; b++)
                if (compare.compare(p.slots[region[b]], p.slots[region[best]]) < 0) best = b;
            if (best == a) continue;
            int i = region[a], j = region[best];
            p.click(j);
            p.click(i);
            if (!p.cursor.empty()) p.click(j);
        }
        return p.finish(0);
    }

    /** null eligibleKeys means all items; otherwise only those already present at the destination. */
    public static Plan transfer(List<Stack> initial, int[] sources, int[] destinations, Set<Integer> eligibleKeys) {
        validate(initial, sources, destinations);
        ClickPlan p = new ClickPlan(initial);
        int transferred = 0;
        for (int source : sources) {
            Stack original = p.slots[source];
            if (original.empty() || (eligibleKeys != null && !eligibleKeys.contains(original.key))) continue;
            List<Integer> targets = new ArrayList<>();
            for (int destination : destinations) {
                Stack s = p.slots[destination];
                if (!s.empty() && s.key == original.key && s.count < s.limit) targets.add(destination);
            }
            for (int destination : destinations) if (p.slots[destination].empty()) targets.add(destination);
            if (targets.isEmpty()) continue;
            p.click(source);
            for (int destination : targets) {
                p.click(destination);
                if (p.cursor.empty()) break;
            }
            if (!p.cursor.empty()) p.click(source);
            transferred += original.count - p.slots[source].count;
        }
        return p.finish(transferred);
    }
    public static Set<Integer> keys(List<Stack> stacks, int[] region) {
        Set<Integer> keys = new HashSet<>();
        for (int i : region) if (!stacks.get(i).empty()) keys.add(stacks.get(i).key);
        return keys;
    }
    private ClickPlan() { throw new AssertionError(); }
}
