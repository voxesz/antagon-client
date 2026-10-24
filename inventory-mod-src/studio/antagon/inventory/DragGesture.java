package studio.antagon.inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Separates a normal click from a sweep, visiting each permitted slot just once. */
public final class DragGesture {
    private final int origin;
    private final Set<Integer> allowed;
    private final Set<Integer> seen = new HashSet<>();
    private final List<Integer> fresh = new ArrayList<>();
    private boolean holding = true;
    private boolean swept;
    public DragGesture(int origin, Set<Integer> allowed) {
        if (!allowed.contains(origin)) throw new IllegalArgumentException("Espaço inicial inválido para arrastar");
        this.origin = origin;
        this.allowed = Set.copyOf(allowed);
    }
    public void move(int slot) {
        if (!holding || !allowed.contains(slot)) return;
        if (!swept && slot != origin) {
            swept = true;
            select(origin);
        }
        if (swept) select(slot);
    }
    private void select(int slot) { if (seen.add(slot)) fresh.add(slot); }
    public List<Integer> drain() {
        List<Integer> selected = List.copyOf(fresh);
        fresh.clear();
        return selected;
    }
    /** A release without crossing another inventory slot is an ordinary click. */
    public boolean release() { holding = false; return !swept; }
    public boolean holding() { return holding; }
    public int origin() { return origin; }
}
